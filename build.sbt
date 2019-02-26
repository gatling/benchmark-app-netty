scalaVersion := "2.12.8"

scalacOptions := Seq(
  "-encoding", "UTF-8", "-target:jvm-1.8", "-deprecation",
  "-feature", "-unchecked", "-language:implicitConversions", "-language:postfixOps")

libraryDependencies += "io.netty"                    % "netty-codec-http"                % "4.1.33.Final"
libraryDependencies += "io.netty"                    % "netty-handler"                   % "4.1.33.Final"
libraryDependencies += "io.netty"                    % "netty-transport-native-epoll"    % "4.1.33.Final" classifier "linux-x86_64"
libraryDependencies += "io.netty"                    % "netty-tcnative-boringssl-static" % "2.0.20.Final"
libraryDependencies += "org.slf4j"                   % "slf4j-api"                       % "1.7.25"
libraryDependencies += "ch.qos.logback"              % "logback-classic"                 % "1.2.3"
libraryDependencies += "com.typesafe.scala-logging"  %% "scala-logging"                  % "3.9.0"
