/*
 * Copyright 2015 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import play.core.PlayVersion
import sbt.Keys._
import sbt._

object HmrcBuild extends Build {
  import BuildDependencies._
  import uk.gov.hmrc.DefaultBuildSettings._
  import uk.gov.hmrc.SbtAutoBuildPlugin
  import uk.gov.hmrc.versioning.SbtGitVersioning


  val appName = "metrix"

  val appDependencies = Seq(
    "com.typesafe.play"      %% "play"                 % PlayVersion.current % "provided",
    "uk.gov.hmrc"            %% "simple-reactivemongo" % "6.0.0",
    "de.threedimensions"     %% "metrics-play"         % "2.5.13"            % "provided",
    "com.codahale.metrics"   %  "metrics-graphite"     % "3.0.2"             % "provided",
    "uk.gov.hmrc"            %% "mongo-lock"           % "5.0.0",

    "org.scalatest"          %% "scalatest"            % "2.2.4"             % "test",
    ("org.pegdown"           %  "pegdown"              % "1.4.2" cross CrossVersion.Disabled) % "test",
    "org.scalacheck"         %% "scalacheck"           % "1.11.4"            % "test",
    "com.typesafe.play"      %% "play-test"            % PlayVersion.current % "test",
    "uk.gov.hmrc"            %% "hmrctest"             % "2.3.0"             % "test",
    "org.mockito"            % "mockito-all"           % "1.9.5"             % "test",
    "uk.gov.hmrc"            %% "reactivemongo-test"   % "3.0.0"             % "test"
  )

  lazy val microservice = Project(appName, file("."))
    .enablePlugins(SbtAutoBuildPlugin, SbtGitVersioning)
    .settings(scalaSettings: _*)
    .settings(defaultSettings(): _*)
    .settings(
      targetJvm := "jvm-1.8",
      libraryDependencies ++= appDependencies,
      crossScalaVersions := Seq("2.11.7", "2.10.4")
    )
    .settings(
      resolvers := Seq(
        Resolver.bintrayRepo("hmrc", "releases"),
        Resolver.typesafeRepo("releases"),
        Resolver.jcenterRepo
      )
    )
}
