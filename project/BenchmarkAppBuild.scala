import sbt._
import sbt.Keys._

import BuildSettings._
import Dependencies._

object BenchmarkAppBuild extends Build {

  val root = Project("benchmark-app-netty", file("."))
    .settings(allSettings: _*)
    .settings(libraryDependencies ++= allDeps)
}
