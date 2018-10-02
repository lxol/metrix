import PlayCrossCompilation._
import uk.gov.hmrc.DefaultBuildSettings.defaultSettings

enablePlugins(SbtAutoBuildPlugin, SbtGitVersioning, SbtArtifactory)

name := "metrix"

makePublicallyAvailableOnBintray := true

majorVersion                     := 3

defaultSettings()

scalaVersion := "2.11.11"

libraryDependencies ++= LibDependencies()

crossScalaVersions := Seq("2.11.8")

resolvers := Seq(
  Resolver.bintrayRepo("hmrc", "releases"),
  "typesafe-releases" at "http://repo.typesafe.com/typesafe/releases/"
)

playCrossCompilationSettings
