package com.evolution.natseffect.jetstream

import cats.effect.IO
import cats.effect.std.Queue
import weaver.GlobalRead

import scala.concurrent.duration.*

class PacedKeyValueSpec(global: GlobalRead) extends JetStreamSpec(global) {

  testResource("watchPaced for KV changes") { ctx =>
    for {
      (_, kv, _) <- setupKeyValueBucket(ctx)

      queue <- Queue.bounded[IO, io.nats.client.api.KeyValueEntry](10).toResource

      _ <- kv.put("config.host", "localhost".getBytes()).toResource

      subscription <- kv.watchPaced(
        keys = List("config.*"),
        watchMode = KvWatchMode.LatestValues,
        handler = entry => queue.offer(entry),
        warmupTimeout = 5.seconds
      )

      warmupResult <- subscription.warmupLatch.get.toResource

      _ <- kv.put("config.port", "8080".getBytes()).toResource
      _ <- kv.put("other.key", "ignored".getBytes()).toResource     // Should not appear in watch
      _ <- kv.put("config.host", "127.0.0.1".getBytes()).toResource // Update

      // Collect watch events
      entry1 <- queue.take.toResource
      entry2 <- queue.take.toResource
      entry3 <- queue.take.toResource

    } yield matches(warmupResult) { case Warmup.Result.Success(_) => success } &&
      // Should receive events for config.* keys only
      expect.eql(entry1.getKey, "config.host") &&
      expect.eql(new String(entry1.getValue), "localhost") &&
      expect.eql(entry2.getKey, "config.port") &&
      expect.eql(new String(entry2.getValue), "8080") &&
      expect.eql(entry3.getKey, "config.host") &&
      expect.eql(new String(entry3.getValue), "127.0.0.1")
  }

  // Warmup on an empty bucket must complete immediately via the consumer-info pending check,
  // without waiting out the first pull window
  testResource("watchAllPaced on an empty bucket completes warmup immediately") { ctx =>
    for {
      (_, kv, _) <- setupKeyValueBucket(ctx)

      subscription <- kv.watchAllPaced(
        watchMode = KvWatchMode.LatestValues,
        handler = _ => IO.unit,
        warmupTimeout = 10.seconds
      )

      warmupResult <- subscription.warmupLatch.get.timeout(5.seconds).toResource

    } yield matches(warmupResult) { case Warmup.Result.Success(time) => expect(time < 3.seconds) }
  }
}
