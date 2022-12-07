package org.renci.materializer

import org.http4s.HttpRoutes
import org.http4s.blaze.server.BlazeServerBuilder
import org.http4s.server.Router
import org.http4s.server.middleware.CORS
import org.phenoscape.scowl._
import org.renci.materializer.Materializer.IsConsistent
import org.semanticweb.owlapi.apibinding.OWLManager
import org.semanticweb.owlapi.model.{AddOntologyAnnotation, OWLOntology}
import sttp.apispec.openapi.circe.yaml._
import sttp.tapir.PublicEndpoint
import sttp.tapir.docs.openapi.OpenAPIDocsInterpreter
import sttp.tapir.server.http4s.ztapir.ZHttp4sServerInterpreter
import sttp.tapir.swagger.SwaggerUI
import sttp.tapir.ztapir._
import zio._
import zio.interop.catz._
import cats.implicits._

object MaterializerServer {

  private val materializeEndpoint: PublicEndpoint[(OWLOntology, Boolean, Boolean, Boolean), String, OWLOntology, Any] =
    endpoint
      .in("materialize")
      .post
      .in(oneOfBody(
        stringBodyUtf8AnyFormat(Codecs.owlFunctionalSyntax),
        stringBodyUtf8AnyFormat(Codecs.owlTurtleSyntax),
        stringBodyUtf8AnyFormat(Codecs.owlRDFXMLSyntax)
      ))
      .in(query[Boolean]("direct").default(false))
      .in(query[Boolean]("mark-direct").default(true))
      .in(query[Boolean]("allow-inconsistent").default(false))
      .out(oneOfBody(
        stringBodyUtf8AnyFormat(Codecs.owlFunctionalSyntax),
        stringBodyUtf8AnyFormat(Codecs.owlTurtleSyntax),
        stringBodyUtf8AnyFormat(Codecs.owlRDFXMLSyntax)
      ))
      .errorOut(stringBody)

  private val materializeService: ZServerEndpoint[Materializer, Any] = materializeEndpoint.zServerLogic { case (ontology, direct, markDirect, allowInconsistent) =>
    ZIO.service[Materializer].flatMap { mat =>
      mat.materializeAbox(ontology, allowInconsistent, markDirect, !direct)
        .map(ZIO.succeed(_))
        .getOrElse(emptyInconsistenOntology())
    }
      .mapError(_.getMessage)
  }

  private val openAPI: String = OpenAPIDocsInterpreter().toOpenAPI(List(materializeEndpoint), "Materializer API", "1.0").toYaml

  private def emptyInconsistenOntology(): Task[OWLOntology] = ZIO.attempt {
    val manager = OWLManager.createOWLOntologyManager()
    val ont = manager.createOntology()
    manager.applyChange(new AddOntologyAnnotation(ont, Annotation(IsConsistent, false)))
    ont
  }

  private val materializeHttp: HttpRoutes[RIO[Materializer, *]] = ZHttp4sServerInterpreter().from(materializeService).toRoutes

  private val docsRoute: HttpRoutes[RIO[Materializer, *]] = ZHttp4sServerInterpreter[Materializer]().from(SwaggerUI[RIO[Materializer, *]](openAPI)).toRoutes

  val serverProgram: RIO[Materializer, Unit] =
    ZIO.executor.flatMap(executor =>
      BlazeServerBuilder[RIO[Materializer, *]]
        .withExecutionContext(executor.asExecutionContext)
        .bindHttp(8080, "0.0.0.0")
        .withHttpApp(CORS.policy.withAllowOriginAll
          .withAllowCredentials(false)
          .apply(Router("/" -> (materializeHttp <+> docsRoute) ).orNotFound))
        .withResponseHeaderTimeout(120.seconds.asScala)
        .withIdleTimeout(180.seconds.asScala)
        .serve
        .compile
        .drain
    )

}
