package com.evolution.natseffect.loadtest

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import com.evolution.natseffect.{Connection, Consumer, ErrorListener, Message}

import java.lang.management.ManagementFactory
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*

/** Plain JVM counters, updated from jnats callback threads and IO handlers but readable from raw watchdog threads even when the CE compute
  * pool is wedged.
  */
final class Counters {
  val messagesHandled: AtomicLong    = new AtomicLong(0)
  val slowConsumerEvents: AtomicLong = new AtomicLong(0)
  val errorsOccurred: AtomicLong     = new AtomicLong(0)
  val exceptionsOccurred: AtomicLong = new AtomicLong(0)
  val outgoingDiscards: AtomicLong   = new AtomicLong(0)
  val peakHeapBytes: AtomicLong      = new AtomicLong(0)

  val livenessLagMillis: ConcurrentLinkedQueue[Long] = new ConcurrentLinkedQueue[Long]

  def livenessSamples: Vector[Long] = livenessLagMillis.iterator().asScala.toVector
}

/** Counts error-listener events. The increments happen synchronously in the method body, NOT inside the returned effect:
  * UnwrappedErrorListener invokes these methods on the jnats callback thread to build the effect and only then dispatches it to the CE
  * compute pool - which this harness deliberately starves. Counting inside the effect would queue the increments behind the very burst
  * being measured, biasing the metrics downward exactly when the pathology peaks (and losing whatever is still queued at shutdown).
  */
final class CountingErrorListener(counters: Counters) extends ErrorListener[IO] {

  private def countNow(counter: AtomicLong): IO[Unit] = {
    counter.incrementAndGet()
    IO.unit
  }

  override def errorOccurred(conn: Connection[IO], error: String): IO[Unit] =
    countNow(counters.errorsOccurred)

  override def exceptionOccurred(conn: Connection[IO], exp: Exception): IO[Unit] =
    countNow(counters.exceptionsOccurred)

  override def slowConsumerDetected(conn: Connection[IO], consumer: Consumer[IO]): IO[Unit] =
    countNow(counters.slowConsumerEvents)

  /** Outgoing-queue discards (discardMessagesWhenOutgoingQueueFull) - lost publishes, e.g. during populate; distinct from consumer-side
    * drops, which come from connection statistics.
    */
  override def messageDiscarded(conn: Connection[IO], msg: Message): IO[Unit] =
    countNow(counters.outgoingDiscards)

  override def socketWriteTimeout(conn: Connection[IO]): IO[Unit] =
    countNow(counters.errorsOccurred)
}

object Probes {

  /** Simulates a k8s liveness probe: a fiber on the COMPUTE pool that expects to run every 50ms and records by how much it was late. Under
    * compute-pool starvation the recorded lag explodes - the "liveness probe stops responding" symptom.
    */
  def livenessProbe(counters: Counters): Resource[IO, Unit] = {
    val tick = for {
      t0 <- IO.monotonic
      _  <- IO.sleep(50.millis)
      t1 <- IO.monotonic
      _ <- IO.delay {
        counters.livenessLagMillis.add((t1 - t0 - 50.millis).toMillis.max(0L))
        ()
      }
    } yield ()

    tick.foreverM.background.void
  }

  /** Peak-heap sampler on a raw daemon thread so it keeps sampling even when the compute pool is starved. */
  def heapSampler(counters: Counters): Resource[IO, Unit] =
    Resource
      .make(
        IO.delay {
          val stop = new AtomicBoolean(false)
          val t = new Thread(
            () =>
              while (!stop.get()) {
                val used = Runtime.getRuntime.totalMemory() - Runtime.getRuntime.freeMemory()
                counters.peakHeapBytes.getAndAccumulate(used, math.max)
                Thread.sleep(100)
              },
            "loadtest-heap-sampler"
          )
          t.setDaemon(true)
          t.start()
          stop
        }
      )(stop => IO.delay(stop.set(true)))
      .void

  /** Deadlock detector on a raw daemon thread, started from main BEFORE the IO runtime executes anything, and disarmed only via the
    * returned flag right before the process exits - so it covers runtime construction, the whole program, report rendering AND
    * runtime.shutdown(). If the flag is not set within config.globalTimeout, it dumps all thread stacks and partial counters, then kills
    * the JVM with exit code 2. A wedged compute pool cannot do any of this for itself - that is the point.
    */
  def startWatchdog(config: Config, counters: Counters): AtomicBoolean = {
    val done = new AtomicBoolean(false)
    val t = new Thread(
      () => {
        val deadline = System.nanoTime() + config.globalTimeout.toNanos
        while (!done.get() && System.nanoTime() < deadline) Thread.sleep(200)
        if (!done.get()) {
          println()
          println(s"!!! WATCHDOG: run did not finish within ${config.globalTimeout.toSeconds}s - assuming DEADLOCK")
          println(
            s"!!! partial counters: handled=${counters.messagesHandled.get} slowConsumers=${counters.slowConsumerEvents.get} " +
              s"errors=${counters.errorsOccurred.get} exceptions=${counters.exceptionsOccurred.get} peakHeapMiB=${counters.peakHeapBytes.get / 1024 / 1024}"
          )
          println(
            s"!!! note: halt skips all cleanup - the embedded nats-server child may be left running on port ${config.port} " +
              "(the next run cleans up the bucket, but the stray process must be killed manually, e.g. pkill nats-server)"
          )
          println("!!! thread dump:")
          ManagementFactory.getThreadMXBean.dumpAllThreads(true, true).foreach(t => println(t.toString))
          Runtime.getRuntime.halt(2)
        }
      },
      "loadtest-watchdog"
    )
    t.setDaemon(true)
    t.start()
    done
  }

  def percentile(sortedSamples: Vector[Long], p: Double): Long =
    if (sortedSamples.isEmpty) 0L
    else {
      val idx = math.ceil(p / 100.0 * sortedSamples.size).toInt - 1
      sortedSamples(idx.max(0).min(sortedSamples.size - 1))
    }
}
