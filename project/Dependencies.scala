import sbt._

object Dependencies {

  private val nettyHttp            = "io.netty"                    % "netty-codec-http"                % "4.1.22.Final"
  private val nettyHandler         = "io.netty"                    % "netty-handler"                   % nettyHttp.revision
  private val nettyNativeTransport = "io.netty"                    % "netty-transport-native-epoll"    % nettyHttp.revision classifier "linux-x86_64"
  private val nettyBoringSsl       = "io.netty"                    % "netty-tcnative-boringssl-static" % "2.0.7.Final"
  private val slf4j                = "org.slf4j"                   % "slf4j-api"                       % "1.7.25"
  private val logback              = "ch.qos.logback"              % "logback-classic"                 % "1.2.3"
  private val scalaLogging         = "com.typesafe.scala-logging"  %% "scala-logging"                  % "3.7.2"
  private val commonsIo            = "commons-io"                  % "commons-io"                      % "2.6"

  val allDeps = Seq(nettyHttp, nettyHandler, nettyNativeTransport, nettyBoringSsl, slf4j, logback, scalaLogging, commonsIo)
}
