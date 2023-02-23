enablePlugins(JavaAppPackaging)

organization := "org.renci"

name := "materializer"

version := "0.2.7"

licenses := Seq("MIT license" -> url("https://opensource.org/licenses/MIT"))

scalaVersion := "2.13.10"

scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8")

javaOptions += "-Xmx8G"

testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")

addCompilerPlugin("org.typelevel" % "kind-projector" % "0.13.2" cross CrossVersion.full)

val zioVersion = "2.0.5"
val tapirVersion = "1.2.3"
val http4sVersion = "0.23.11"

libraryDependencies ++= {
  Seq(
    "dev.zio"                     %% "zio"                     % zioVersion,
    "dev.zio"                     %% "zio-streams"             % zioVersion,
    "com.softwaremill.sttp.tapir" %% "tapir-zio"               % tapirVersion,
    "com.softwaremill.sttp.tapir" %% "tapir-http4s-server-zio" % tapirVersion,
    "com.softwaremill.sttp.tapir" %% "tapir-swagger-ui-bundle" % tapirVersion,
    "dev.zio"                     %% "zio-interop-cats"        % "23.0.0.2",
    "org.http4s"                  %% "http4s-blaze-server"     % http4sVersion,
    "org.geneontology"            %% "whelk-owlapi"            % "1.1.2",
    "org.geneontology"            %% "arachne"                 % "1.3" exclude ("com.outr", "scribe-slf4j"),
    "com.outr"                    %% "scribe-slf4j"            % "2.7.13",
    "com.github.alexarchambault"  %% "case-app"                % "2.0.6",
    "org.apache.jena"              % "apache-jena-libs"        % "4.6.1" exclude ("org.slf4j", "slf4j-log4j12"),
    "dev.zio"                     %% "zio-test"                % zioVersion % Test,
    "dev.zio"                     %% "zio-test-sbt"            % zioVersion % Test
  )
}

dockerBaseImage := "openjdk:17-buster"
dockerUsername := Some("balhoff")
dockerExposedPorts := Seq(8080)
dockerUpdateLatest := true
