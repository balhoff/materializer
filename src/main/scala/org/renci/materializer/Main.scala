package org.renci.materializer

import caseapp._
import org.apache.commons.io.FileUtils
import org.apache.jena.graph.{Node, NodeFactory, Triple}
import org.apache.jena.query.{Query, QueryExecutionFactory, QueryFactory}
import org.apache.jena.rdf.model.Model
import org.apache.jena.riot.system.{StreamRDF, StreamRDFWriter}
import org.apache.jena.riot.{RDFDataMgr, RDFFormat}
import org.apache.jena.sparql.core.Quad
import org.apache.jena.vocabulary.{OWL2, RDF}
import org.semanticweb.owlapi.apibinding.OWLManager
import org.semanticweb.owlapi.model._
import zio._
import zio.stream._

import java.io.{File, FileOutputStream}
import java.lang.System.currentTimeMillis
import scala.jdk.CollectionConverters._
import scala.util.Using

object Main extends ZCommandApp[MaterializerConfig] {

  private val ProvDerivedFrom = NodeFactory.createURI("http://www.w3.org/ns/prov#wasDerivedFrom")

  override def run(command: MaterializerConfig, arg: RemainingArgs): URIO[Scope, ExitCode] = {
    val program = command match {
      case config: MaterializerConfig.File =>
        for {
          quadsWriter <- createStreamRDF(config.output)
          ontology <- loadOntology(config.ontologyFile)
          filterGraphQueryOpt <- ZIO.foreach(config.filterGraphQuery)(loadFilterGraphQuery)
          reasoner = config.reasoner.construct(ontology)
          _ <- ZIO.succeed(scribe.info("Prepared reasoner"))
          start <- ZIO.succeed(currentTimeMillis())
          inferencesStream = streamModels(config.input, config.parallelism)
            .filter(model => filterGraphQueryOpt.forall(q => shouldUseGraph(q, model.model)))
            .mapZIOParUnordered(config.parallelism)(computeInferences(_, reasoner, config))
            .tap {
              case _: Inferences      => ZIO.unit
              case Inconsistent(path) => ZIO.succeed(scribe.warn(s"Inconsistent dataset: $path"))
            }
            .collect { case inf: Inferences => inf }
          _ <- inferencesStream.foreach { case Inferences(_, graphOpt, triples) =>
            writeModel(graphOpt, triples, quadsWriter)
          }
          stop <- ZIO.succeed(currentTimeMillis())
          _ <- ZIO.succeed(scribe.info(s"Reasoning done in: ${(stop - start) / 1000.0}s"))
        } yield ()
      case s: MaterializerConfig.Server    =>
        val reasonerLayer = ZLayer.fromZIO(loadOntology(s.ontologyFile).map(s.reasoner.construct))
        MaterializerServer.serverProgram.provide(reasonerLayer)
    }
    program.tapError(e => ZIO.succeed(e.printStackTrace())).exitCode
  }

  def computeInferences(model: ModelFromPath, materializer: Materializer, config: MaterializerConfig.File): Task[Result] = {
    for {
      modelIRI <- ZIO.fromOption(findModelIRI(model.model))
        .orElseFail(new Exception(s"Dataset with no ontology IRI in file ${model.path}"))
      graphOpt = determineOutGraph(modelIRI, config).map(NodeFactory.createURI)
      provenanceOpt = graphOpt.map(g => Triple.create(g, ProvDerivedFrom, NodeFactory.createURI(modelIRI)))
      inferred = materializer.materialize(model.model, config.outputInconsistent.bool, config.markDirectTypes.bool, config.outputIndirectTypes.bool)
    } yield {
      inferred match {
        case Some(inferences) => Inferences(model.path, graphOpt, inferences ++ provenanceOpt)
        case None             => Inconsistent(model.path)
      }
    }
  }

  def writeModel(graphOpt: Option[Node], triples: Set[Triple], quadsWriter: StreamRDF): Task[Unit] =
    ZIO.attemptBlocking {
      graphOpt match {
        case Some(graph) => triples.foreach(t => quadsWriter.quad(Quad.create(graph, t)))
        case None        => triples.foreach(quadsWriter.triple)
      }
    }

  def loadOntology(path: String): Task[OWLOntology] = for {
    manager <- ZIO.attempt(OWLManager.createOWLOntologyManager())
    ontology <- ZIO.attemptBlocking(manager.loadOntology(IRI.create(new File(path))))
    _ <- ZIO.succeed(scribe.info("Loaded ontology"))
  } yield ontology

  def streamModels(path: String, parallelism: Int): Stream[Throwable, ModelFromPath] = {
    val fileOrDirectory = new File(path)
    val filesZ = for {
      isDirectory <- ZIO.attempt(fileOrDirectory.isDirectory)
      inputFiles <- if (isDirectory)
        ZIO.attemptBlocking(FileUtils.listFiles(fileOrDirectory, null, true).asScala.to(List))
      else ZIO.succeed(List(fileOrDirectory))
    } yield inputFiles
    ZStream.fromIterableZIO(filesZ).flatMapPar(parallelism)(file => loadModelsFromFile(file.getPath))
  }

  def loadModelsFromFile(filePath: String): Stream[Throwable, ModelFromPath] = {
    val models = ZIO.attemptBlocking(RDFDataMgr.loadDataset(filePath)).map {
      dataset =>
        val defaultModel = ModelFromPath(dataset.getDefaultModel, filePath)
        val namedModels = dataset.listNames().asScala.map(graphName => ModelFromPath(dataset.getNamedModel(graphName), filePath)).to(List)
        defaultModel :: namedModels
    }
    ZStream.fromIterableZIO(models)
  }

  def createStreamRDF(path: String): ZIO[Scope, Throwable, StreamRDF] = {
    ZIO.acquireRelease(ZIO.attempt(new FileOutputStream(new File(path))))(stream => ZIO.succeed(stream.close())).flatMap { outputStream =>
      ZIO.acquireRelease(ZIO.attempt {
        val stream = StreamRDFWriter.getWriterStream(outputStream, RDFFormat.NQUADS, null)
        stream.start()
        stream
      })(stream => ZIO.succeed(stream.finish()))
    }
  }

  def determineOutGraph(modelIRI: String, config: MaterializerConfig.File): Option[String] =
    config.outputGraphName.map {
      name =>
        if (config.suffixGraph.bool) modelIRI + name
        else name
    }

  def findModelIRI(model: Model): Option[String] =
    model.listSubjectsWithProperty(RDF.`type`, OWL2.Ontology).asScala
      .find(_.isURIResource)
      .map(_.getURI)

  def loadFilterGraphQuery(path: String): Task[Query] =
    ZIO.attemptBlocking(QueryFactory.read(path))
      .filterOrFail(_.isAskType)(new Exception("Filter graph query must be ASK type"))

  def shouldUseGraph(query: Query, model: Model): Boolean =
    Using.resource(QueryExecutionFactory.create(query, model))(_.execAsk())

  final case class ModelFromPath(model: Model, path: String)

  sealed trait Result extends Product with Serializable {

    def path: String

  }

  final case class Inferences(path: String, graph: Option[Node], triples: Set[Triple]) extends Result

  final case class Inconsistent(path: String) extends Result

}
