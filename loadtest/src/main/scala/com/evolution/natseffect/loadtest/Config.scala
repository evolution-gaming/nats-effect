package com.evolution.natseffect.loadtest

import scala.collection.mutable
import scala.concurrent.duration.{DurationInt, DurationLong, FiniteDuration}

sealed trait Scenario {
  def name: String
}

object Scenario {

  /** Historically: jnats default pending limits (512Ki messages / 64 MiB per dispatcher), reproducing the incident's client-side drops,
    * slow-consumer events, ordered-consumer recreations, warmup timeouts and liveness stalls. Since #10 hardcoded unlimited pending limits
    * for JetStream dispatchers, this configuration is no longer reachable via the public API - on current master this scenario is identical
    * to [[Unlimited]]; the recorded drop-reproduction numbers in docs/loadtest-results.md are from pre-#10 master.
    */
  case object Baseline extends Scenario { val name = "baseline" }

  /** The interim fix, baked into master by #10: unlimited pending limits on JetStream dispatchers - no drops, but the overload remains
    * (control-plane timeouts, heartbeat-driven recreations, unbounded memory for genuinely slow consumers).
    */
  case object Unlimited extends Scenario { val name = "unlimited" }

  val all: List[Scenario] = List(Baseline, Unlimited)

  def parse(s: String): Either[String, Scenario] =
    all.find(_.name == s).toRight(s"Unknown scenario '$s', expected one of: ${all.map(_.name).mkString(", ")}")
}

final case class Config(
  scenario: Scenario,
  watchers: Int = 20,
  keys: Int = 18000,
  valueSizeBytes: Int = 12 * 1024,
  warmupTimeout: FiniteDuration = 60.seconds,
  handlerSpinMicros: Int = 5,
  populateParallelism: Int = 64,
  port: Int = 4331,
  computeThreads: Int = 2,
  globalTimeout: FiniteDuration = 6.minutes
) {
  def totalBytes: Long = keys.toLong * valueSizeBytes

  def describe: String =
    s"scenario=${scenario.name} watchers=$watchers keys=$keys valueSize=${valueSizeBytes}B " +
      s"warmupTimeout=${warmupTimeout.toSeconds}s spinMicros=$handlerSpinMicros computeThreads=$computeThreads port=$port " +
      s"globalTimeout=${globalTimeout.toSeconds}s"
}

object Config {

  /** Parses key=value arguments, e.g.: scenario=baseline watchers=20 keys=18000 valueSize=12288 warmupTimeoutSec=60 computeThreads=2. Every
    * numeric argument is range-checked here so that a typo fails fast with exit code 1 instead of surfacing as an obscure runtime crash
    * (or, for computeThreads=0, a compute pool with zero workers that would hang the app before the watchdog is even armed).
    */
  def parse(args: List[String]): Either[String, Config] = {
    val (badArgs, pairs) = args.partitionMap { arg =>
      arg.split("=", 2) match {
        case Array(k, v) => Right(k -> v)
        case _           => Left(s"Expected key=value argument, got '$arg'")
      }
    }

    badArgs.headOption.toLeft(pairs.toMap).flatMap { map =>
      // Every key passed to int() registers itself, so the unknown-argument check below cannot drift from the parse sites.
      val consumedKeys = mutable.Set("scenario")

      def int(key: String, default: Int, min: Int, max: Int = Int.MaxValue): Either[String, Int] = {
        consumedKeys += key
        map.get(key) match {
          case None => Right(default)
          case Some(v) =>
            v.toIntOption
              .filter(i => i >= min && i <= max)
              .toRight(s"'$key' must be an integer >= $min${if (max != Int.MaxValue) s" and <= $max" else ""}, got '$v'")
        }
      }

      for {
        scenario    <- Scenario.parse(map.getOrElse("scenario", Scenario.Baseline.name))
        watchers    <- int("watchers", 20, min = 1)
        keys        <- int("keys", 18000, min = 1)
        valueSize   <- int("valueSize", 12 * 1024, min = 1)
        warmupSec   <- int("warmupTimeoutSec", 60, min = 1)
        spinMicros  <- int("spinMicros", 5, min = 0)
        populatePar <- int("populateParallelism", 64, min = 1)
        port        <- int("port", 4331, min = 1, max = 65535)
        threads     <- int("computeThreads", 2, min = 1)
        // The watchdog must outlive a legitimate warmup, so the default derives from warmupTimeout and an explicit
        // value below warmup + 30s is rejected - otherwise a healthy run would be halted as a spurious DEADLOCK.
        timeoutSec <- int("timeoutSec", default = math.max(360, warmupSec * 3 + 30), min = warmupSec + 30)
        unknown     = map.keySet -- consumedKeys
        _          <- Either.cond(unknown.isEmpty, (), s"Unknown arguments: ${unknown.mkString(", ")}")
      } yield Config(
        scenario = scenario,
        watchers = watchers,
        keys = keys,
        valueSizeBytes = valueSize,
        warmupTimeout = warmupSec.toLong.seconds,
        handlerSpinMicros = spinMicros,
        populateParallelism = populatePar,
        port = port,
        computeThreads = threads,
        globalTimeout = timeoutSec.toLong.seconds
      )
    }
  }
}
