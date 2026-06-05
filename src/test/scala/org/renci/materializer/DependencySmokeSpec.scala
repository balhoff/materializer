package org.renci.materializer

import org.apache.jena.graph.{NodeFactory, Triple}
import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.vocabulary.RDF
import org.semanticweb.owlapi.model.OWLOntology
import sttp.tapir.DecodeResult
import zio.test._

object DependencySmokeSpec extends ZIOSpecDefault {

  private val Example = "http://example.org/materializer-test#"

  private val ontologyText =
    s"""Prefix(:=<$Example>)
       |Ontology(<${Example}ontology>
       |  Declaration(Class(:Child))
       |  Declaration(Class(:Parent))
       |  SubClassOf(:Child :Parent)
       |)
       |""".stripMargin

  override def spec: Spec[TestEnvironment, Any] =
    suite("dependency smoke tests")(
      test("decodes and encodes an OWL ontology through the Tapir codec") {
        val ontology = decodeOntology(ontologyText)
        val encoded = Codecs.owlFunctionalSyntax.encode(ontology)

        assertTrue(ontology.getAxiomCount > 0) &&
          assertTrue(encoded.contains("SubClassOf"))
      },
      test("materializes a simple subclass inference with Jena and Arachne") {
        val materializer = ArachneMaterializer(decodeOntology(ontologyText))
        val model = ModelFactory.createDefaultModel()
        val individual = model.createResource(s"${Example}individual")

        model.add(individual, RDF.`type`, model.createResource(s"${Example}Child"))

        val inferred = materializer.materialize(
          model,
          allowInconsistent = false,
          markDirectTypes = false,
          assertIndirectTypes = true
        )
        val expected = Triple.create(
          NodeFactory.createURI(s"${Example}individual"),
          RDF.`type`.asNode(),
          NodeFactory.createURI(s"${Example}Parent")
        )

        assertTrue(inferred.exists(_.contains(expected)))
      }
    )

  private def decodeOntology(text: String): OWLOntology =
    Codecs.owlFunctionalSyntax.decode(text) match {
      case DecodeResult.Value(ontology) => ontology
      case DecodeResult.Error(_, error) => throw error
      case other                        => throw new RuntimeException(s"Unexpected decode result: $other")
    }

}
