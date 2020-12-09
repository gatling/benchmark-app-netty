scalaVersion := "2.13.4"

scalacOptions := Seq(
  "-encoding", "UTF-8", "-target:jvm-1.8", "-deprecation",
  "-feature", "-unchecked", "-language:implicitConversions", "-language:postfixOps")

libraryDependencies += "io.netty"                    % "netty-codec-http"                % "4.1.55.Final"
libraryDependencies += "io.netty"                    % "netty-handler"                   % "4.1.55.Final"
libraryDependencies += "io.netty"                    % "netty-transport-native-epoll"    % "4.1.55.Final" classifier "linux-x86_64"
libraryDependencies += "io.netty"                    % "netty-tcnative-boringssl-static" % "2.0.35.Final"
libraryDependencies += "org.slf4j"                   % "slf4j-api"                       % "1.7.30"
libraryDependencies += "ch.qos.logback"              % "logback-classic"                 % "1.2.3"
libraryDependencies += "com.typesafe.scala-logging" %% "scala-logging"                   % "3.9.2"
libraryDependencies += "com.typesafe"                % "config"                          % "1.4.1"
libraryDependencies += "org.apache.commons"          % "commons-math3"                   % "3.6.1"
