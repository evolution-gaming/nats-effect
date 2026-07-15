package com.evolution.natseffect.jetstream

import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import com.evolution.natseffect.Nats
import com.evolution.natseffect.impl.WrappedConnection
import io.nats.client.api.{KeyValueConfiguration, StorageType}
import weaver.GlobalRead

import scala.concurrent.duration.*

/** The incident shape that motivated the paced engine: many watchers on a populated bucket with a slow handler. The callback engine
  * accumulates an unbounded client-side backlog in this scenario until jnats pending limits drop messages, which forces ordered-consumer
  * recreation storms. The paced engine must complete the same load with zero drops and zero consumer recreations, keeping strict
  * per-watcher ordering.
  */
class PacedStressSpec(global: GlobalRead) extends JetStreamSpec(global) {

  private val Watchers = 8
  private val KeyCount = 1500

  testResource("many paced watchers with a slow handler: no drops, no recreates, strict order") { ctx =>
    for {
      bucketName <- randomBucketName.toResource
      connection <- Nats.connect(ctx.url())
      js         <- JetStream.fromConnection[IO](connection).toResource
      kvm        <- js.keyValueManagement().toResource

      _ <- Resource.make(
        kvm.create(
          KeyValueConfiguration
            .builder()
            .name(bucketName)
            .storageType(StorageType.Memory)
            .build()
        )
      )(_ => kvm.delete(bucketName).void)

      kv <- js.keyValue(bucketName).toResource

      // Bounded parallelism: each put occupies a blocking-pool thread
      _ <- (1 to KeyCount).toList
        .grouped(50)
        .toList
        .traverse_(chunk => chunk.parTraverse(i => kv.put(s"key.$i", s"value-$i".getBytes())))
        .toResource

      watchers <- (1 to Watchers).toList.traverse { _ =>
        for {
          listener  <- CountingPacedListener.make.toResource
          revisions <- Ref.of[IO, List[Long]](Nil).toResource
          subscription <- kv.watchPaced(
            keys = List(">"),
            watchMode = KvWatchMode.LatestValues,
            handler = entry => IO.sleep(1.millis) *> revisions.update(entry.getRevision :: _),
            warmupTimeout = 60.seconds,
            metaDataOnly = false,
            listener = listener
          )
        } yield (listener, revisions, subscription)
      }

      warmups <- watchers.parTraverse { case (_, _, subscription) => subscription.warmupLatch.get }.toResource

      results <- watchers.traverse {
        case (listener, revisions, _) =>
          (revisions.get.map(_.reverse), listener.resubscribes, listener.processed, listener.pulls).tupled
      }.toResource

      dropped <- IO(connection.asInstanceOf[WrappedConnection[IO]].asJava.getStatistics.getDroppedCount).toResource

    } yield {
      val warmedUp         = warmups.collect { case Warmup.Result.Success(_) => () }.size
      val deliveredCounts  = results.map { case (revisions, _, _, _) => revisions.size }
      val orderedPerWatch  = results.map { case (revisions, _, _, _) => revisions.zip(revisions.tail).forall { case (a, b) => a < b } }
      val resubscribeTotal = results.map { case (_, resubscribes, _, _) => resubscribes }.sum
      val processedCounts  = results.map { case (_, _, processed, _) => processed }
      // Pacing sanity: pulls scale with batches - KeyCount / 500 (default batch) for the warmup
      // drain plus a small idle-window allowance - not with messages
      val pullsBounded = results.forall { case (_, _, _, pulls) => pulls <= KeyCount / 500 + 5 }

      expect.eql(warmedUp, Watchers) &&
      expect.eql(deliveredCounts, List.fill(Watchers)(KeyCount)) &&
      expect(orderedPerWatch.forall(identity)) &&
      expect.eql(resubscribeTotal, 0) &&
      expect.eql(processedCounts, List.fill(Watchers)(KeyCount)) &&
      expect(pullsBounded) &&
      expect.eql(dropped, 0L)
    }
  }
}
