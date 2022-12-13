package org.renci.materializer

import org.apache.jena.graph.{Triple => JenaTriple}
import org.apache.jena.rdf.model._
import org.apache.jena.vocabulary.{OWL2, RDF, RDFS, XSD}
import org.eclipse.rdf4j.model.{BNode, IRI => SesameIRI, Literal => SesameLiteral, Statement => SesameStatement}
import org.eclipse.rdf4j.rio.helpers.StatementCollector
import org.geneontology.jena.OWLtoRules
import org.geneontology.rules.engine.{Literal, _}
import org.geneontology.rules.util.Bridge
import org.geneontology.rules.util.Bridge.IndirectTypesContainer
import org.phenoscape.scowl._
import org.renci.materializer.Materializer.IsConsistent
import org.semanticweb.owlapi.apibinding.OWLManager
import org.semanticweb.owlapi.model.parameters.Imports
import org.semanticweb.owlapi.model.{AddOntologyAnnotation, OWLAxiom, OWLOntology}
import org.semanticweb.owlapi.rio.RioRenderer

import java.lang.System.currentTimeMillis
import scala.collection.mutable
import scala.jdk.CollectionConverters._

class ArachneMaterializer(ontology: OWLOntology) extends Materializer {

  private val IndirectType = URI(OWLtoRules.IndirectType)
  private val DirectTypeURI = URI(Materializer.DirectType.getURI)
  private val DirectTypeAP = AnnotationProperty(Materializer.DirectType.getURI)
  private val RDFType = URI(RDF.`type`.getURI)
  private val RDFLangString = URI(RDF.langString.getURI)
  private val Nothing = URI(OWL2.Nothing.getURI)
  private val ontRules = Bridge.injectIndirectTypeActions(Bridge.rulesFromJena(OWLtoRules.translate(ontology, Imports.INCLUDED, true, true, false, true)).to(Set), ontology)
  private val arachne = new RuleEngine(ontRules, false)

  scribe.info(s"Constructed rule engine with ${ontRules.size} rules")

  System.gc()

  private def materializeTriples(triples: Iterable[Triple], allowInconsistent: Boolean, markDirectTypes: Boolean, assertIndirectTypes: Boolean): Option[Set[Triple]] = {
    val start = currentTimeMillis()
    val wm = arachne.processTriples(triples)
    val stop = currentTimeMillis()
    scribe.info(s"Rule engine done in: ${(stop - start)}ms")
    val inferred = wm.facts.to(Set) -- wm.asserted
    val inconsistent = isInconsistent(inferred)
    if (!allowInconsistent && inconsistent) None
    else {
      val finalTriples =
        if (markDirectTypes || !assertIndirectTypes) {
          val indirectTypes = wm.userInfo match {
            case container: IndirectTypesContainer => container.indirectTypes
            case _                                 => Map.empty[URI, Set[URI]]
          }
          val maybeWithoutIndirects = if (!assertIndirectTypes) {
            inferred.filterNot(isIndirectType(_, indirectTypes))
          } else inferred
          if (markDirectTypes) {
            val assertedTypeTriples = triples.filter(_.p == RDFType).filterNot(t => isBuiltIn(t.o)).toSet
            val assertedTypeTriplesThatAreDirect = assertedTypeTriples.filterNot(isIndirectType(_, indirectTypes))
            val allRemainingTypeTriples = maybeWithoutIndirects.filter(t => t.p == RDFType)
            val directRDFTypeTriples = allRemainingTypeTriples.filterNot(isIndirectType(_, indirectTypes))
            val directTypeTriples = (directRDFTypeTriples ++ assertedTypeTriplesThatAreDirect).map(t => Triple(t.s, DirectTypeURI, t.o))
            maybeWithoutIndirects ++ directTypeTriples
          } else maybeWithoutIndirects
        } else inferred
      Some(finalTriples)
    }
  }

  private def isIndirectType(triple: Triple, indirectTypes: scala.collection.Map[URI, Set[URI]]) = triple match {
    case Triple(s: URI, RDFType, o: URI) => indirectTypes.getOrElse(s, Set.empty)(o)
    case _                               => false
  }

  override def materialize(model: Model, allowInconsistent: Boolean, markDirectTypes: Boolean, assertIndirectTypes: Boolean): Option[Set[JenaTriple]] = {
    val triples = model.listStatements().asScala.map(t => Bridge.tripleFromJena(t.asTriple())).to(Iterable)
    materializeTriples(triples, allowInconsistent, markDirectTypes, assertIndirectTypes)
      .map(triples => triples.map(Bridge.jenaFromTriple))
  }

  override def materializeAbox(abox: OWLOntology, allowInconsistent: Boolean, markDirectTypes: Boolean, assertIndirectTypes: Boolean): Option[OWLOntology] = {
    val assertedTriples = ontologyAsTriples(abox)
    materializeTriples(assertedTriples, allowInconsistent, markDirectTypes, assertIndirectTypes).map { triples =>
      val axioms = mutable.Set[OWLAxiom]()
      var inconsistent = false
      triples.foreach {
        case Triple(URI(s), RDFType, oURI @ URI(o)) =>
          axioms.add(ClassAssertion(Class(o), Individual(s)))
          if (oURI == Nothing) inconsistent = true
        case Triple(URI(s), DirectTypeURI, URI(o))  =>
          val unannotated = ClassAssertion(Class(o), Individual(s))
          axioms.remove(unannotated)
          axioms.add(unannotated.getAnnotatedAxiom(Set(Annotation(DirectTypeAP, true)).asJava))
        case Triple(URI(s), URI(p), URI(o))         =>
          axioms.add(ObjectPropertyAssertion(ObjectProperty(p), Individual(s), Individual(o)))
        case _                                      => ()
      }
      val manager = OWLManager.createOWLOntologyManager()
      val ont = manager.createOntology(axioms.asJava)
      if (inconsistent) manager.applyChange(new AddOntologyAnnotation(ont, Annotation(IsConsistent, false)))
      ont
    }
  }

  private def isInconsistent(triples: Set[Triple]): Boolean =
    triples.exists(t => t.o == Nothing && t.p == RDFType)

  private def isBuiltIn(node: ConcreteNode): Boolean = node match {
    case URI(n) => n.startsWith(OWL2.NS) || n.startsWith(RDFS.getURI) || n.startsWith(RDF.getURI) || n.startsWith(XSD.getURI)
    case _      => false
  }

  private def ontologyAsTriples(ontology: OWLOntology): Set[Triple] = {
    val collector = new StatementCollector()
    new RioRenderer(ontology, collector, null).render()
    collector.getStatements.asScala.map(sesameTripleToArachne).toSet
  }

  def sesameTripleToArachne(triple: SesameStatement): Triple = {
    val subject = triple.getSubject match {
      case bnode: BNode   => BlankNode(bnode.getID)
      case iri: SesameIRI => URI(iri.stringValue)
    }
    val predicate = URI(triple.getPredicate.stringValue)
    val obj = triple.getObject match {
      case bnode: BNode                                            => BlankNode(bnode.getID)
      case iri: SesameIRI                                          => URI(iri.stringValue)
      case literal: SesameLiteral if literal.getLanguage.isPresent =>
        Literal(literal.getLabel, RDFLangString, Some(literal.getLanguage.get()))
      case literal: SesameLiteral                                  =>
        Literal(literal.getLabel, URI(literal.getDatatype.stringValue), None)
    }
    Triple(subject, predicate, obj)
  }

}

object ArachneMaterializer {

  def apply(ontology: OWLOntology): ArachneMaterializer =
    new ArachneMaterializer(ontology)

}
