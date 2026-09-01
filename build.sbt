val nettyVersion = "4.2.17.Final"
val nettyTcNativeVersion = "2.0.83.Final"

lazy val benchmarkAppNetty = rootProject
  .enablePlugins(GatlingAutomatedScalafixPlugin, GatlingAutomatedScalafmtPlugin)
  .settings(
    scalaVersion := "2.13.18",
    scalacOptions := Seq(
      "-encoding",
      "UTF-8",
      "-target:jvm-1.8",
      "-deprecation",
      "-feature",
      "-unchecked",
      "-language:implicitConversions",
      "-language:postfixOps"
    ),
    libraryDependencies ++= Seq(
      "io.netty"                    % "netty-codec-http"                % nettyVersion,
      "io.netty"                    % "netty-codec-http2"               % nettyVersion,
      "io.netty"                    % "netty-handler"                   % nettyVersion,
      "io.netty"                    % "netty-pkitesting"                % nettyVersion,
      ("io.netty"                   % "netty-transport-native-epoll"    % nettyVersion).classifier("linux-aarch_64"),
      ("io.netty"                   % "netty-transport-native-epoll"    % nettyVersion).classifier("linux-x86_64"),
      ("io.netty"                   % "netty-transport-native-io_uring" % nettyVersion).classifier("linux-aarch_64"),
      ("io.netty"                   % "netty-transport-native-io_uring" % nettyVersion).classifier("linux-x86_64"),
      "io.netty"                    % "netty-tcnative-classes"          % nettyTcNativeVersion,
      "io.netty"                    % "netty-tcnative-boringssl-static" % nettyTcNativeVersion,
      "org.bouncycastle"            % "bcpkix-jdk18on"                  % "1.85",
      "org.slf4j"                   % "slf4j-api"                       % "2.0.18",
      "ch.qos.logback"              % "logback-classic"                 % "1.6.3",
      "com.typesafe.scala-logging" %% "scala-logging"                   % "3.9.6",
      "com.typesafe"                % "config"                          % "1.4.9",
      "org.apache.commons"          % "commons-math3"                   % "3.6.1"
    )
  )
