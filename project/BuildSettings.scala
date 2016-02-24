import sbt._
import sbt.Keys._

import com.typesafe.sbt.SbtScalariform
import com.typesafe.sbt.SbtScalariform.ScalariformKeys
import net.virtualvoid.sbt.graph.Plugin.graphSettings

object BuildSettings {

  val basicSettings = Seq(
    organization := "io.gatling",
    resolvers    := Seq(Opts.resolver.sonatypeSnapshots),
    javaOptions  := Seq("-XX:+UseG1GC", "-Xms512M", "-Xmx512M", "-XX:+AggressiveOpts", "-XX:+OptimizeStringConcat", "-XX:+UseFastAccessorMethods", "-Djava.net.preferIPv4Stack=true", "-Djava.net.preferIPv6Addresses=false"),
    scalaVersion := "2.11.7",
    scalacOptions := Seq(
      "-encoding", "UTF-8",
      "-deprecation",
      "-Xlint",
      "-feature",
      "-unchecked",
      "-language:implicitConversions")
  )

  val allSettings =
    basicSettings ++ formattingSettings

  /*************************/
  /** Formatting settings **/
  /*************************/

  lazy val formattingSettings = SbtScalariform.scalariformSettings ++ Seq(
    ScalariformKeys.preferences := formattingPreferences
  )

  import scalariform.formatter.preferences._

  def formattingPreferences =
    FormattingPreferences()
      .setPreference(DoubleIndentClassDeclaration, true)
      .setPreference(AlignParameters, true)
      .setPreference(AlignSingleLineCaseStatements, true)
      .setPreference(IndentLocalDefs, true)

}
