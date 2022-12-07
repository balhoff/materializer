package org.renci.materializer

import org.apache.jena.graph.{Node, NodeFactory, Triple}
import org.apache.jena.rdf.model.Model
import org.eclipse.rdf4j.model.vocabulary.SESAME
import org.phenoscape.scowl._
import org.semanticweb.owlapi.model.{OWLAnnotationProperty, OWLOntology}

trait Materializer {

  def materialize(model: Model, allowInconsistent: Boolean, markDirectTypes: Boolean, assertIndirectTypes: Boolean): Option[Set[Triple]]

  def materializeAbox(abox: OWLOntology, allowInconsistent: Boolean, markDirectTypes: Boolean, assertIndirectTypes: Boolean): Option[OWLOntology]

}

object Materializer {

  val DirectType: Node = NodeFactory.createURI(SESAME.DIRECTTYPE.stringValue())

  val IsConsistent: OWLAnnotationProperty = AnnotationProperty("https://github.com/INCATools/ubergraph#isConsistent")

}
