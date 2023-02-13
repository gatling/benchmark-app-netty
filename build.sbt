scalaVersion := "2.13.10"

scalacOptions := Seq(
  "-encoding", "UTF-8", "-target:jvm-1.8", "-deprecation",
  "-feature", "-unchecked", "-language:implicitConversions", "-language:postfixOps")

libraryDependencies += "io.netty"                    % "netty-codec-http"                % "4.1.89.Final"
libraryDependencies += "io.netty"                    % "netty-handler"                   % "4.1.89.Final"
libraryDependencies += "io.netty"                    % "netty-transport-native-epoll"    % "4.1.89.Final" classifier "linux-x86_64"
libraryDependencies += "io.netty"                    % "netty-tcnative-classes"          % "2.0.58.Final"
libraryDependencies += "io.netty"                    % "netty-tcnative-boringssl-static" % "2.0.58.Final"
libraryDependencies += "io.netty.incubator"          % "netty-incubator-transport-native-io_uring" % "0.0.17.Final" classifier "linux-x86_64"
libraryDependencies += "org.slf4j"                   % "slf4j-api"                       % "1.7.36"
libraryDependencies += "ch.qos.logback"              % "logback-classic"                 % "1.2.11"
libraryDependencies += "com.typesafe.scala-logging" %% "scala-logging"                   % "3.9.5"
libraryDependencies += "com.typesafe"                % "config"                          % "1.4.2"
libraryDependencies += "org.apache.commons"          % "commons-math3"                   % "3.6.1"
