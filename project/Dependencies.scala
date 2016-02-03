import sbt._

object Dependencies {

  private val netty        = "io.netty"                      % "netty-codec-http"     % "4.0.34.Final"
  private val javassist    = "org.javassist"                 % "javassist"            % "3.20.0-GA"
  private val slf4j        = "org.slf4j"                     % "slf4j-api"            % "1.7.14"
  private val logback      = "ch.qos.logback"                % "logback-classic"      % "1.1.3"
  private val scalalogging = "com.typesafe.scala-logging"   %% "scala-logging-slf4j"  % "2.1.2"

  val allDeps = Seq(netty, javassist, slf4j, logback, scalalogging)
}
