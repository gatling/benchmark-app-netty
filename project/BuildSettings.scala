import sbt._
import sbt.Keys._

import com.typesafe.sbt.SbtScalariform
import com.typesafe.sbt.SbtScalariform.ScalariformKeys
import net.virtualvoid.sbt.graph.Plugin.graphSettings

object BuildSettings {

  val basicSettings = Seq(
    organization := "io.gatling",
    scalaVersion := "2.11.5",
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
