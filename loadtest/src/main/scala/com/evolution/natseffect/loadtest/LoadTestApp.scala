package com.evolution.natseffect.loadtest

import cats.effect.IO
import cats.effect.unsafe.{IORuntime, IORuntimeConfig, Scheduler}

import java.util.Locale
import scala.concurrent.ExecutionContext

/** Load test reproducing the KV warmup collapse (slow consumers -> drops -> ordered-consumer recreation -> compute-pool exhaustion) and
  * measuring consumption performance. Results are recorded in docs/loadtest-results.md.
  *
  * Run:
  * {{{
  *   sbt 'loadtest/run scenario=baseline'
  *   sbt 'loadtest/run scenario=unlimited'
  *   sbt 'loadtest/run scenario=baseline watchers=20 keys=18000 valueSize=12288 computeThreads=2'
  * }}}
  *
  * Exit codes: 0 - finished (see verdict in the report), 1 - bad arguments, 2 - watchdog killed a deadlocked run (thread dump printed), 3 -
  * crashed with an exception.
  *
  * Not an IOApp on purpose: the embedded nats-server wrapper leaks non-daemon executor threads that survive close() and would prevent JVM
  * exit, so this main builds its own runtime (with the compute pool restricted to config.computeThreads, mimicking pod CPU limits) and
  * leaves through sys.exit explicitly. The watchdog is started before the runtime executes anything and disarmed last, so runtime
  * construction, the program, report rendering and runtime.shutdown() are all covered.
  */
object LoadTestApp {

  def main(args: Array[String]): Unit =
    Config.parse(args.toList) match {
      case Left(err) =>
        println(s"error: $err")
        sys.exit(1)

      case Right(config) =>
        // Reproducible report formatting and a machine-parseable RESULT_JSON regardless of the host locale
        // (e.g. comma decimal separators or localized digits).
        Locale.setDefault(Locale.ROOT)

        val counters     = new Counters
        val watchdogDone = Probes.startWatchdog(config, counters)

        // Upcast the tuple to erase the WSTP poller type parameter, which Scala 2 cannot express without existentials
        val computeTuple: (ExecutionContext, Any, () => Unit) =
          IORuntime.createWorkStealingComputeThreadPool(threads = config.computeThreads)
        val (compute, _, computeDown)  = computeTuple
        val (blocking, blockingDown)   = IORuntime.createDefaultBlockingExecutionContext()
        val (scheduler, schedulerDown) = Scheduler.createDefaultScheduler()

        implicit val runtime: IORuntime =
          IORuntime(
            compute,
            blocking,
            scheduler,
            () => {
              computeDown()
              blockingDown()
              schedulerDown()
            },
            IORuntimeConfig()
          )

        val program = for {
          _      <- IO.println(s"starting load test: ${config.describe}")
          result <- Probes.heapSampler(counters).surround(Runner.run(config, counters))
          _      <- IO.println(Report.render(result))
        } yield ()

        val exitCode =
          try {
            program.unsafeRunSync()
            0
          } catch {
            case e: Throwable =>
              println(s"load test crashed: $e")
              e.printStackTrace()
              3
          } finally runtime.shutdown()

        watchdogDone.set(true)
        sys.exit(exitCode)
    }
}
