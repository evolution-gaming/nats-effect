package com.evolution.natseffect.loadtest

import cats.effect.{IO, Resource}
import cats.effect.implicits.*
import cats.syntax.all.*
import com.evolution.natseffect.jetstream.{EmbeddedNats, JetStream, KeyValue, KvWatchMode, Warmup}
import com.evolution.natseffect.{Nats, Options}
import io.nats.client.api.{KeyValueConfiguration, KeyValueEntry, KeyValueOperation, StorageType}

import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration.{DurationInt, FiniteDuration}

final case class WatcherOutcome(
  idx: Int,
  result: Either[Throwable, Warmup.Result],
  consumerNames: Int
) {

  /** Sampled lower bound: names are polled, so recreations replaced within one poll interval are not observed. */
  def recreations: Int = (consumerNames - 1).max(0)
}

final case class RunResult(
  config: Config,
  populateTime: FiniteDuration,
  consumeWallTime: FiniteDuration,
  outcomes: List[WatcherOutcome],
  messagesHandled: Long,
  droppedMessages: Long,
  slowConsumerEvents: Long,
  errorsOccurred: Long,
  exceptionsOccurred: Long,
  outgoingDiscards: Long,
  livenessLagsMillis: Vector[Long],
  peakHeapBytes: Long
)

object Runner {

  def run(config: Config, counters: Counters): IO[RunResult] =
    resources(config, counters).use {
      case (kv, droppedCount) =>
        for {
          populateTime <- populate(kv, config)
          _ <- IO.println(s"populated ${config.keys} keys in ${populateTime.toMillis / 1000.0}s, starting ${config.watchers} watchers")

          // droppedCount is cumulative over the connection lifetime; snapshot after populate so the report
          // attributes only consume-phase drops to the watcher burst.
          droppedBaseline <- droppedCount

          result <- Probes.livenessProbe(counters).surround(consume(kv, config, counters))

          (consumeWallTime, outcomes) = result
          droppedTotal               <- droppedCount
        } yield RunResult(
          config = config,
          populateTime = populateTime,
          consumeWallTime = consumeWallTime,
          outcomes = outcomes,
          messagesHandled = counters.messagesHandled.get,
          droppedMessages = droppedTotal - droppedBaseline,
          slowConsumerEvents = counters.slowConsumerEvents.get,
          errorsOccurred = counters.errorsOccurred.get,
          exceptionsOccurred = counters.exceptionsOccurred.get,
          outgoingDiscards = counters.outgoingDiscards.get,
          livenessLagsMillis = counters.livenessSamples,
          peakHeapBytes = counters.peakHeapBytes.get
        )
    }

  /** Embedded real nats-server + one shared connection (as in production) + a fresh KV bucket. Yields the KeyValue client and an effect
    * reading the connection-wide dropped-message count.
    */
  private def resources(config: Config, counters: Counters): Resource[IO, (KeyValue[IO], IO[Long])] =
    for {
      // Fail fast with a readable message when the port is taken - typically an orphaned nats-server from a
      // previous run killed by the watchdog's halt (which skips all finalizers).
      _ <- Resource.eval(checkPortFree(config.port))

      // shutdownHook = true so Ctrl-C on this plain-main app still stops the server child process even though
      // no resource finalizer runs.
      server <- EmbeddedNats.resource(config.port, shutdownHook = true)

      options = {
        val base = Options[IO]()
          .withNatsServerUris(Vector(server.url()))
          .withConnectionName(Some(s"loadtest-${config.scenario.name}"))
          .withErrorListener(Some(new CountingErrorListener(counters)))
        config.scenario match {
          case Scenario.Baseline  => base
          case Scenario.Unlimited => base.withPendingLimits(0, 0)
        }
      }

      connection <- Nats.connect(options)
      js         <- JetStream.fromConnection[IO](connection).toResource
      kvm        <- js.keyValueManagement().toResource

      bucketName = s"loadtest-${config.scenario.name}"
      // JetStream file storage persists across process death; a bucket left behind by a halted run would fail
      // the create (or corrupt the measurement), so delete any leftover first.
      _ <- Resource.eval(kvm.delete(bucketName).attempt.void)
      _ <- Resource.make(
        kvm.create(
          KeyValueConfiguration
            .builder()
            .name(bucketName)
            .storageType(StorageType.File)
            .build()
        )
      )(_ => kvm.delete(bucketName).void)

      kv <- js.keyValue(bucketName).toResource
    } yield (kv, connection.statistics.flatMap(_.droppedCount))

  private def checkPortFree(port: Int): IO[Unit] =
    IO.blocking {
      try new ServerSocket(port).close()
      catch {
        case e: java.io.IOException =>
          throw new IllegalStateException(
            s"port $port is already in use - likely an orphaned nats-server from a halted run; " +
              s"kill it (pkill nats-server) or pass port=<other>",
            e
          )
      }
    }

  private def populate(kv: KeyValue[IO], config: Config): IO[FiniteDuration] =
    for {
      value <- IO.delay {
        val a = new Array[Byte](config.valueSizeBytes)
        scala.util.Random.nextBytes(a)
        a
      }
      progress = new AtomicLong(0)
      t0      <- IO.monotonic
      _ <- (0 until config.keys).toVector.parTraverseN(config.populateParallelism) { i =>
        kv.put(f"key$i%06d", value) *> IO.delay {
          val n = progress.incrementAndGet()
          if (n % 5000 == 0) println(s"  populated $n/${config.keys}")
        }
      }
      t1 <- IO.monotonic
    } yield t1 - t0

  /** Starts all watchers gated on a latch so they hit the server simultaneously (the incident scenario), waits for every warmup latch to
    * resolve, returns total wall time and per-watcher outcomes.
    */
  private def consume(kv: KeyValue[IO], config: Config, counters: Counters): IO[(FiniteDuration, List[WatcherOutcome])] =
    for {
      gate <- IO.deferred[Unit]

      fibers <- (0 until config.watchers).toList.traverse { i =>
        (gate.get *> watcher(kv, config, counters, i)).start
      }

      t0       <- IO.monotonic
      _        <- gate.complete(())
      outcomes <- fibers.traverse(_.joinWithNever)
      t1       <- IO.monotonic
    } yield (t1 - t0, outcomes)

  /** One KV watcher, mirroring KeyValueReader: watchAll(LatestValues), a per-entry cache update, and the warmup latch.
    *
    * The handler reproduces the per-message compute cost of KeyValueReader.impl (Ref update with revision comparison - keep in sync with
    * KeyValueReader.scala) so the measured load matches the production path. It stores only the revision, not the entry: retaining values
    * would put watchers x bucket (20 x 211 MiB) on the harness heap and turn every scenario into an OOM test.
    *
    * A background poller samples the jnats consumer name; ordered consumers get a new server-side name each time jnats recreates them after
    * a sequence gap, so distinct names - 1 is a LOWER BOUND on recreations (a name replaced within one poll interval is never observed, and
    * the poller itself runs on the starved compute pool).
    *
    * A watcher that dies (e.g. "Timeout or no response waiting for NATS JetStream server" while the control plane is starved by the burst)
    * is itself a pathology data point, so failures are captured in the outcome instead of crashing the run.
    */
  private def watcher(kv: KeyValue[IO], config: Config, counters: Counters, idx: Int): IO[WatcherOutcome] =
    (IO.ref(Set.empty[String]), IO.ref(Map.empty[String, Long])).flatMapN { (names, cache) =>
      val handler = (kvEntry: KeyValueEntry) => {
        val cacheUpdate = kvEntry.getOperation match {
          case KeyValueOperation.PUT =>
            cache.update(
              _.updatedWith(kvEntry.getKey) {
                case Some(oldRevision) => Some(if (kvEntry.getRevision > oldRevision) kvEntry.getRevision else oldRevision)
                case None              => Some(kvEntry.getRevision)
              }
            )
          case KeyValueOperation.DELETE | KeyValueOperation.PURGE =>
            cache.update(_.updatedWith(kvEntry.getKey) {
              case Some(oldRevision) => if (kvEntry.getRevision > oldRevision) None else Some(oldRevision)
              case None              => None
            })
        }

        cacheUpdate *> IO.delay {
          counters.messagesHandled.incrementAndGet()
          spin(config.handlerSpinMicros)
        }
      }

      // Exhaustive on purpose: a new scenario (e.g. fetch) must pick its consume engine here or compilation fails
      // with a non-exhaustive-match error - otherwise it would silently load-test watch semantics.
      val consumeOnce: IO[Warmup.Result] = config.scenario match {
        case Scenario.Baseline | Scenario.Unlimited =>
          kv.watchAll(KvWatchMode.LatestValues, handler, config.warmupTimeout)
            .use { sub =>
              val pollName = {
                sub.subscription.getConsumerName.attempt.flatMap {
                  case Right(name) if name != null => names.update(_ + name)
                  case _                           => IO.unit
                } *> IO.sleep(50.millis)
              }.foreverM

              pollName.background.surround(sub.warmupLatch.get)
            }
      }

      consumeOnce.attempt.flatMap { result =>
        names.get.map(ns => WatcherOutcome(idx, result, ns.size))
      }
    }

  private def spin(micros: Int): Unit =
    if (micros > 0) {
      val deadline = System.nanoTime() + micros * 1000L
      while (System.nanoTime() < deadline) {}
    }
}
