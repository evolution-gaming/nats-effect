// Manual-only load-test harness: no test sources, so CI's `sbt check test` merely compiles this
// module and never executes a run - the load test starts only via an explicit `sbt loadtest/run`.
name := "nats-effect-loadtest"

publish / skip := true

libraryDependencies += "berlin.yuna" % "nats-server" % "2.12.1"

Compile / run / fork := true

Compile / run / javaOptions ++= Seq("-Xmx4g", "-XX:+UseG1GC")
