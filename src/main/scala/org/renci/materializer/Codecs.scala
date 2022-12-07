package org.renci.materializer

import org.apache.commons.io.input.CharSequenceInputStream
import org.semanticweb.owlapi.apibinding.OWLManager
import org.semanticweb.owlapi.formats.{FunctionalSyntaxDocumentFormat, RDFXMLDocumentFormat, TurtleDocumentFormat}
import org.semanticweb.owlapi.model.{OWLDocumentFormat, OWLOntology}
import sttp.model.MediaType
import sttp.tapir.DecodeResult.{Error, Value}
import sttp.tapir.{Codec, CodecFormat}

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import scala.util.{Try, Using}

object Codecs {

  final case class TextOWLFunctional() extends CodecFormat {
    override val mediaType: MediaType = MediaType("text", "owl-functional", Some("UTF-8"))
  }

  final case class TextTurtle() extends CodecFormat {
    override val mediaType: MediaType = MediaType("text", "turtle", Some("UTF-8"))
  }

  final case class ApplicationRDFXML() extends CodecFormat {
    override val mediaType: MediaType = MediaType("application", "rdf+xml")
  }

  implicit val owlFunctionalSyntax: Codec[String, OWLOntology, TextOWLFunctional] =
    owlCodec(new FunctionalSyntaxDocumentFormat(), TextOWLFunctional())

  implicit val owlTurtleSyntax: Codec[String, OWLOntology, TextTurtle] =
    owlCodec(new TurtleDocumentFormat(), TextTurtle())

  implicit val owlRDFXMLSyntax: Codec[String, OWLOntology, ApplicationRDFXML] =
    owlCodec(new RDFXMLDocumentFormat(), ApplicationRDFXML())

  private def owlCodec[F <: CodecFormat](format: OWLDocumentFormat, codecFormat: F): Codec[String, OWLOntology, F] =
    Codec.string.mapDecode { text =>
      Try {
        val manager = OWLManager.createOWLOntologyManager()
        Using.resource(new CharSequenceInputStream(text, StandardCharsets.UTF_8)) { stream =>
          manager.loadOntologyFromOntologyDocument(stream)
        }
      }.fold(Error(text, _), Value(_))
    } { ontology =>
      Using.resource(new ByteArrayOutputStream()) { stream =>
        ontology.getOWLOntologyManager.saveOntology(ontology, format, stream)
        stream.toString(StandardCharsets.UTF_8.name())
      }
    }
      .format(codecFormat)

}
