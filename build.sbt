import sbt.Test

lazy val commonSettings = Seq(
  homepage             := Some(uri("https://github.com/evolution-gaming/nats-effect")),
  organization         := "com.evolution",
  organizationName     := "Evolution",
  organizationHomepage := Some(uri("https://evolution.com")),
  startYear            := Some(2026),
  licenses             := Seq(("MIT", uri("https://opensource.org/licenses/MIT"))),
  crossScalaVersions   := Seq("2.13.18", "3.3.8"),
  versionScheme        := Some("semver-spec"),
  scalaVersion         := crossScalaVersions.value.head,
  publishTo            := Some(Resolver.evolutionReleases),
  scalacOptions ++= {
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, _)) =>
        Seq(
          "-Wunused:_",
          "-Werror",
          "-deprecation",
          "-Wnumeric-widen",
          "-Wdead-code",
          "-Wvalue-discard",
          "-Xsource:3",
          "-feature",
          "-Yrangepos",
          "-Ywarn-unused"
        )
      case _ =>
        Seq(
          "-source:future",
          "-Wunused:all",
          "-Werror",
          "-deprecation",
          "-feature",
          "-language:adhocExtensions",
          // improve error messages:
          "-explain",
          "-explain-types"
        )
    }
  }
)

lazy val root = project
  .in(file("."))
  .settings(commonSettings)
  .settings(
    name                     := "nats-effect",
    publish / skip           := true,
    Test / parallelExecution := true,
    addCommandAlias("fmt", "all scalafmtRepo"),
    addCommandAlias("check", "all scalafmtCheckRepo"),
    addCommandAlias("build", "+all compile testFull")
  )
  .aggregate(core, jetstream, metrics, logback, loadtest)

lazy val core = project.settings(commonSettings).settings(Test / testFrameworks += TestFrameworks.WeaverTestCats)

lazy val jetstream = project.settings(commonSettings).dependsOn(core % "compile->compile;test->test")

lazy val metrics = project.settings(commonSettings).dependsOn(core)

lazy val logback = project.settings(commonSettings).dependsOn(core)

// compile->test so the loadtest can reuse the embedded-server boot helper from jetstream's test sources
lazy val loadtest = project.settings(commonSettings).dependsOn(jetstream % "compile->compile;compile->test")
