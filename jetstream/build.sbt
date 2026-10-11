name := "nats-effect-jetstream"

libraryDependencies ++= {
  CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, _)) => Seq(compilerPlugin(("com.olegpy" %% "better-monadic-for" % "0.3.1").excludeAll(ExclusionRule("org.scala-lang"))))
    case _            => Seq.empty
  }
}
