scalaVersion := "2.13.18"

scalacOptions := Seq(
  "-encoding", "UTF-8", "-target:jvm-1.8", "-deprecation",
  "-feature", "-unchecked", "-language:implicitConversions", "-language:postfixOps")

val nettyVersion = "4.2.9.Final"
val nettyTcNativeVersion = "2.0.74.Final"

enablePlugins(GatlingAutomatedScalafixPlugin, GatlingAutomatedScalafmtPlugin)

libraryDependencies += "io.netty"                    % "netty-codec-http"                % nettyVersion
libraryDependencies += "io.netty"                    % "netty-codec-http2"               % nettyVersion
libraryDependencies += "io.netty"                    % "netty-handler"                   % nettyVersion
libraryDependencies += "io.netty"                    % "netty-pkitesting"                % nettyVersion
libraryDependencies += "io.netty"                    % "netty-transport-native-epoll"    % nettyVersion classifier "linux-aarch_64"
libraryDependencies += "io.netty"                    % "netty-transport-native-epoll"    % nettyVersion classifier "linux-x86_64"
libraryDependencies += "io.netty"                    % "netty-transport-native-io_uring" % nettyVersion classifier "linux-aarch_64"
libraryDependencies += "io.netty"                    % "netty-transport-native-io_uring" % nettyVersion classifier "linux-x86_64"
libraryDependencies += "io.netty"                    % "netty-tcnative-classes"          % nettyTcNativeVersion
libraryDependencies += "io.netty"                    % "netty-tcnative-boringssl-static" % nettyTcNativeVersion
libraryDependencies += "org.bouncycastle"            % "bcpkix-jdk18on"                  % "1.83"
libraryDependencies += "org.slf4j"                   % "slf4j-api"                       % "2.0.17"
libraryDependencies += "ch.qos.logback"              % "logback-classic"                 % "1.5.22"
libraryDependencies += "com.typesafe.scala-logging" %% "scala-logging"                   % "3.9.6"
libraryDependencies += "com.typesafe"                % "config"                          % "1.4.5"
libraryDependencies += "org.apache.commons"          % "commons-math3"                   % "3.6.1"
