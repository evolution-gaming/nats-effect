import sbt.Test

inThisBuild(
  Seq(
    homepage             := Some(url("https://github.com/evolution-gaming/nats-effect")),
    organization         := "com.evolution",
    organizationName     := "Evolution",
    organizationHomepage := Some(url("https://evolution.com")),
    startYear            := Some(2026),
    licenses             := Seq(("MIT", url("https://opensource.org/licenses/MIT"))),
    crossScalaVersions   := Seq("2.13.16", "3.3.4"),
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
            "-language:adhocExtensions"
          )
      }
    }
  )
)

lazy val root = project
  .in(file("."))
  .settings(
    name                     := "nats-effect",
    publish / skip           := true,
    Test / parallelExecution := true,
    addCommandAlias("fmt", "all scalafmtAll scalafmtSbt"),
    addCommandAlias("check", "all scalafmtCheckAll scalafmtSbtCheck"),
    addCommandAlias("build", "+all compile test")
  )
  .aggregate(core, jetstream, metrics, logback)

lazy val core = project.settings(Test / testFrameworks += TestFramework("weaver.framework.CatsEffect"))

lazy val jetstream = project.dependsOn(core % "compile->compile;test->test")

lazy val metrics = project.dependsOn(core)

lazy val logback = project.dependsOn(core)
