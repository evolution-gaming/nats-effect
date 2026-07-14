name := "nats-effect-loadtest"

publish / skip := true

libraryDependencies += "berlin.yuna" % "nats-server" % "2.12.1"

Compile / run / fork := true

Compile / run / javaOptions ++= Seq("-Xmx4g", "-XX:+UseG1GC")
