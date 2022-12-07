package org.renci.materializer

import org.apache.jena.graph._
import org.apache.jena.rdf.model.Model
import org.apache.jena.vocabulary.{OWL2, RDF}
import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import org.eclipse.rdf4j.model.{IRI, Resource, Statement, Value}
import org.geneontology.whelk._
import org.renci.materializer.Materializer.DirectType
import org.semanticweb.owlapi.apibinding.OWLManager
import org.semanticweb.owlapi.formats.RioRDFXMLDocumentFormatFactory
import org.semanticweb.owlapi.model.{OWLOntology, OWLOntologyLoaderConfiguration}
import org.semanticweb.owlapi.rio.{RioMemoryTripleSource, RioParserImpl}

import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

class WhelkMaterializer(ontology: OWLOntology) extends Materializer {

  private val tbox = Bridge.ontologyToAxioms(ontology)
  private val whelk = Reasoner.assert(tbox)
  private val tboxClassAssertions = whelk.classAssertions
  private val tboxRoleAssertions = whelk.roleAssertions
  private val tboxDirectTypes = whelk.individualsDirectTypes
  //ObjectProperty declarations are needed for correct parsing of OWL properties from RDF
  private val allRoles = tbox.flatMap(_.signature).collect { case role: Role => role }
  private val propertyDeclarations = allRoles.map(r => Triple.create(NodeFactory.createURI(r.id), RDF.`type`.asNode(), OWL2.ObjectProperty.asNode()))

  override def materialize(model: Model, allowInconsistent: Boolean, markDirectTypes: Boolean, assertIndirectTypes: Boolean): Option[Set[Triple]] = {
    val triplesWithoutImports = model.listStatements().asScala.filterNot(_.getPredicate == OWL2.imports).map(_.asTriple()).to(Set)
    ontologyFromStatements(triplesWithoutImports ++ propertyDeclarations) match {
      case Some(ont) =>
        val axioms = Bridge.ontologyToAxioms(ont).collect { case ci: ConceptInclusion => ci }
        val assertedClassAssertions = for {
          ConceptInclusion(Nominal(ind), ac @ AtomicConcept(_)) <- axioms
        } yield ConceptAssertion(ac, ind)
        val assertedRoleAssertions = for {
          ConceptInclusion(Nominal(subject), ExistentialRestriction(role @ Role(_), Nominal(target))) <- axioms
        } yield RoleAssertion(role, subject, target)
        val updated = Reasoner.assert(axioms, whelk)
        if (!allowInconsistent && isInconsistent(updated)) None
        else {
          val directTypes = updated.individualsDirectTypes
          val newClassAssertions = (if (assertIndirectTypes) updated.classAssertions
          else directTypes.flatMap { case (individual, types) => types.map(t => ConceptAssertion(t, individual)) }
            ).filterNot(_.concept == BuiltIn.Top).to(Set) -- assertedClassAssertions -- tboxClassAssertions
          val directClassAssertions = if (markDirectTypes) {
            for {
              (whelkInd @ Individual(ind), types) <- directTypes
              knownDirectTypes = tboxDirectTypes.getOrElse(whelkInd, Set.empty)
              c @ AtomicConcept(typ) <- types
              if c != BuiltIn.Top
              if !knownDirectTypes(c)
            } yield Triple.create(NodeFactory.createURI(ind), DirectType, NodeFactory.createURI(typ))
          } else Set.empty
          val newRoleAssertions = updated.roleAssertions -- assertedRoleAssertions -- tboxRoleAssertions
          Some(newClassAssertions.flatMap(ca => classAssertionToTriple(ca)) ++
            newRoleAssertions.map(roleAssertionToTriple) ++
            directClassAssertions)
        }
      case None      =>
        scribe.error("Couldn't create OWL ontology from RDF statements")
        None
    }
  }

  def materializeAbox(abox: OWLOntology, allowInconsistent: Boolean, markDirectTypes: Boolean, assertIndirectTypes: Boolean): Option[OWLOntology] = ???

  private def isInconsistent(reasoner: ReasonerState): Boolean =
    reasoner.closureSubsBySuperclass(BuiltIn.Bottom).exists(_.isInstanceOf[Nominal])

  private def classAssertionToTriple(ca: ConceptAssertion): Option[Triple] = ca match {
    case ConceptAssertion(AtomicConcept(c), Individual(ind)) => Some(Triple.create(NodeFactory.createURI(ind), RDF.`type`.asNode(), NodeFactory.createURI(c)))
    case _                                                   => None
  }

  private def roleAssertionToTriple(ra: RoleAssertion): Triple = Triple.create(NodeFactory.createURI(ra.subject.id), NodeFactory.createURI(ra.role.id), NodeFactory.createURI(ra.target.id))

  private def ontologyFromStatements(triples: Set[Triple]): Option[OWLOntology] = try {
    val statements = triples.flatMap(tripleFromJena)
    val manager = OWLManager.createOWLOntologyManager()
    if (statements.nonEmpty) {
      val source = new RioMemoryTripleSource(statements.asJava)
      val parser = new RioParserImpl(new RioRDFXMLDocumentFormatFactory())
      val newOnt = manager.createOntology()
      parser.parse(source, newOnt, new OWLOntologyLoaderConfiguration())
      Option(newOnt)
    } else None
  } catch {
    case NonFatal(_) => None
  }

  private def tripleFromJena(triple: Triple): Option[Statement] = {
    for {
      subj <- nodeFromJena(triple.getSubject)
      pred <- nodeFromJena(triple.getPredicate)
      obj <- nodeFromJena(triple.getObject)
    } yield SimpleValueFactory.getInstance().createStatement(
      subj.asInstanceOf[Resource],
      pred.asInstanceOf[IRI],
      obj)
  }

  private def nodeFromJena(node: Node): Option[Value] = node match {
    case _: Node_ANY           => None
    case _: Node_Variable      => None
    case uri: Node_URI         => Some(SimpleValueFactory.getInstance().createIRI(uri.getURI))
    case blank: Node_Blank     => Some(SimpleValueFactory.getInstance().createBNode(blank.getBlankNodeLabel))
    case literal: Node_Literal => Some(
      if (literal.getLiteralLanguage == "") SimpleValueFactory.getInstance().createLiteral(literal.getLiteralLexicalForm, literal.getLiteralDatatypeURI)
      else SimpleValueFactory.getInstance().createLiteral(literal.getLiteralLexicalForm, literal.getLiteralLanguage)
    )
  }

}

object WhelkMaterializer {

  def apply(ontology: OWLOntology): WhelkMaterializer =
    new WhelkMaterializer(ontology)

}
