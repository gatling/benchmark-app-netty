import sbt._

object Dependencies {

  private val netty        = "io.netty"                      % "netty-all"            % "4.0.25.Final"
  private val javassist    = "org.javassist"                 % "javassist"            % "3.18.2-GA"
  private val jzlib        = "com.jcraft"                    % "jzlib"                % "1.1.3"
  private val slf4j        = "org.slf4j"                     % "slf4j-api"            % "1.7.10"
  private val logback      = "ch.qos.logback"                % "logback-classic"      % "1.1.2"
  private val scalalogging = "com.typesafe.scala-logging"   %% "scala-logging-slf4j"  % "2.1.2"

  val allDeps = Seq(netty, javassist, jzlib, slf4j, logback, scalalogging)
}
