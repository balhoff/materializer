package org.renci.materializer

import org.apache.jena.datatypes.TypeMapper
import org.apache.jena.graph.{Triple => JenaTriple}
import org.apache.jena.rdf.model._
import org.apache.jena.rdf.model.impl.ResourceImpl
import org.apache.jena.vocabulary.{OWL2, RDF, RDFS, XSD}
import org.eclipse.rdf4j.model.{BNode, Literal, IRI => SesameIRI, Statement => SesameStatement}
import org.eclipse.rdf4j.rio.helpers.StatementCollector
import org.geneontology.jena.OWLtoRules
import org.geneontology.rules.engine.{ConcreteNode, RuleEngine, Triple, URI}
import org.geneontology.rules.util.Bridge
import org.phenoscape.scowl._
import org.renci.materializer.Materializer.IsConsistent
import org.semanticweb.owlapi.apibinding.OWLManager
import org.semanticweb.owlapi.model.parameters.Imports
import org.semanticweb.owlapi.model.{AddOntologyAnnotation, OWLAxiom, OWLOntology}
import org.semanticweb.owlapi.rio.RioRenderer

import scala.collection.mutable
import scala.jdk.CollectionConverters._

class ArachneMaterializer(ontology: OWLOntology) extends Materializer {

  private val IndirectType = URI(OWLtoRules.IndirectType)
  private val DirectTypeURI = URI(Materializer.DirectType.getURI)
  private val DirectTypeAP = AnnotationProperty(Materializer.DirectType.getURI)
  private val RDFType = URI(RDF.`type`.getURI)
  private val Nothing = URI(OWL2.Nothing.getURI)
  private val ontRules = Bridge.rulesFromJena(OWLtoRules.translate(ontology, Imports.INCLUDED, true, true, false, true)).to(Set)
  private val indirectRules = Bridge.rulesFromJena(OWLtoRules.indirectRules(ontology)).to(Set)
  private val arachne = new RuleEngine(ontRules ++ indirectRules, false)

  private def materializeTriples(model: Model, allowInconsistent: Boolean, markDirectTypes: Boolean, assertIndirectTypes: Boolean): Option[Set[Triple]] = {
    val triples = model.listStatements().asScala.map(t => Bridge.tripleFromJena(t.asTriple())).to(Iterable)
    val wm = arachne.processTriples(triples)
    val inferred = wm.facts.to(Set) -- wm.asserted
    val inconsistent = isInconsistent(inferred)
    if (!allowInconsistent && inconsistent) None
    else {
      val (indirectTypeTriples, otherTriples) = inferred.partition(t => t.p == IndirectType)
      val finalTriples =
        if (markDirectTypes || !assertIndirectTypes) {
          val indirectRDFTypeTriples = indirectTypeTriples.map(t => Triple(t.s, RDFType, t.o))
          val maybeWithoutIndirects = if (!assertIndirectTypes) {
            otherTriples -- indirectRDFTypeTriples
          } else otherTriples
          if (markDirectTypes) {
            val assertedTypeTriples = triples.filter(_.p == RDFType).filterNot(t => isBuiltIn(t.o)).toSet
            val assertedTypeTriplesThatAreDirect = assertedTypeTriples -- indirectRDFTypeTriples
            val allRemainingTypeTriples = maybeWithoutIndirects.filter(t => t.p == RDFType)
            val directRDFTypeTriples = allRemainingTypeTriples -- indirectRDFTypeTriples
            val directTypeTriples = (directRDFTypeTriples ++ assertedTypeTriplesThatAreDirect).map(t => Triple(t.s, DirectTypeURI, t.o))
            maybeWithoutIndirects ++ directTypeTriples
          } else maybeWithoutIndirects
        } else inferred -- indirectTypeTriples
      Some(finalTriples)
    }
  }

  override def materialize(model: Model, allowInconsistent: Boolean, markDirectTypes: Boolean, assertIndirectTypes: Boolean): Option[Set[JenaTriple]] = {
    materializeTriples(model, allowInconsistent, markDirectTypes, assertIndirectTypes)
      .map(triples => triples.map(Bridge.jenaFromTriple))
  }

  override def materializeAbox(abox: OWLOntology, allowInconsistent: Boolean, markDirectTypes: Boolean, assertIndirectTypes: Boolean): Option[OWLOntology] = {
    val model = ModelFactory.createDefaultModel().add(ontologyAsTriples(abox).toList.asJava)
    materializeTriples(model, allowInconsistent, markDirectTypes, assertIndirectTypes).map { triples =>
      val axioms = mutable.Set[OWLAxiom]()
      var inconsistent = false
      triples.foreach {
        case Triple(URI(s), RDFType, oURI @ URI(o)) =>
          axioms.add(ClassAssertion(Class(o), Individual(s)))
          if (oURI == Nothing) inconsistent = true
        case Triple(URI(s), DirectTypeURI, URI(o))  =>
          val unannoated = ClassAssertion(Class(o), Individual(s))
          axioms.remove(unannoated)
          axioms.add(unannoated.getAnnotatedAxiom(Set(Annotation(DirectTypeAP, true)).asJava))
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

  private def ontologyAsTriples(ontology: OWLOntology): Set[Statement] = {
    val collector = new StatementCollector()
    new RioRenderer(ontology, collector, null).render()
    collector.getStatements.asScala.map(sesameTripleToJena).toSet
  }

  def sesameTripleToJena(triple: SesameStatement): Statement = {
    val subject = triple.getSubject match {
      case bnode: BNode   => new ResourceImpl(new AnonId(bnode.getID))
      case iri: SesameIRI => ResourceFactory.createResource(iri.stringValue)
    }
    val predicate = ResourceFactory.createProperty(triple.getPredicate.stringValue)
    val obj = triple.getObject match {
      case bnode: BNode                                      => new ResourceImpl(new AnonId(bnode.getID))
      case iri: SesameIRI                                    => ResourceFactory.createResource(iri.stringValue)
      case literal: Literal if literal.getLanguage.isPresent =>
        ResourceFactory.createLangLiteral(literal.getLabel, literal.getLanguage.get())
      case literal: Literal                                  =>
        ResourceFactory.createTypedLiteral(literal.getLabel, TypeMapper.getInstance.getSafeTypeByName(literal.getDatatype.stringValue))
    }
    ResourceFactory.createStatement(subject, predicate, obj)
  }

}

object ArachneMaterializer {

  def apply(ontology: OWLOntology): ArachneMaterializer =
    new ArachneMaterializer(ontology)

}
