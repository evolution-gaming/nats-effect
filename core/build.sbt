name := "nats-effect-core"

libraryDependencies ++= Seq(
  "io.nats"        % "jnats"       % "2.26.3",
  "org.typelevel" %% "cats-effect" % "3.7.1",
  "berlin.yuna"    % "nats-server" % "2.15.0" % Test,
  "org.typelevel" %% "weaver-cats" % "0.13.0" % Test
)
