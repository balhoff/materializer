enablePlugins(JavaAppPackaging)

organization := "org.renci"

name := "materializer"

version := "0.1"

licenses := Seq("MIT license" -> url("https://opensource.org/licenses/MIT"))

scalaVersion := "2.13.6"

scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8")

javaOptions += "-Xmx8G"

testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")

val zioVersion = "1.0.16"

libraryDependencies ++= {
  Seq(
    "dev.zio"                    %% "zio"               % zioVersion,
    "dev.zio"                    %% "zio-streams"       % zioVersion,
    "org.geneontology"           %% "whelk-owlapi"      % "1.1.1",
    "org.geneontology"           %% "arachne"           % "1.2.1" exclude ("com.outr", "scribe-slf4j"),
    "com.outr"                   %% "scribe-slf4j"      % "2.7.10",
    "com.github.alexarchambault" %% "case-app"          % "2.0.6",
    "org.apache.jena"             % "apache-jena-libs"  % "4.2.0" exclude ("org.slf4j", "slf4j-log4j12"),
    "dev.zio"                    %% "zio-test"          % zioVersion % Test,
    "dev.zio"                    %% "zio-test-sbt"      % zioVersion % Test
  )
}
