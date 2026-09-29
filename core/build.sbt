name := "nats-effect-core"

libraryDependencies ++= Seq(
  // Do not bump without fully syncing this library's code with the underlying jnats Java API
  "io.nats"        % "jnats"       % "2.25.1",
  "org.typelevel" %% "cats-effect" % "3.7.1",
  "berlin.yuna"    % "nats-server" % "2.15.0" % Test,
  "org.typelevel" %% "weaver-cats" % "0.13.0" % Test
)
