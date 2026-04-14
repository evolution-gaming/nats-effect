name := "nats-effect-core"

libraryDependencies ++= Seq(
  "io.nats"        % "jnats"       % "2.25.1",
  "org.typelevel" %% "cats-effect" % "3.6.3",
  "berlin.yuna"    % "nats-server" % "2.12.1" % Test,
  "org.typelevel" %% "weaver-cats" % "0.10.1" % Test
)
