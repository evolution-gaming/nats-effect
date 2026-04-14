package com.evolution.natseffect.jetstream

import cats.effect.IO
import cats.effect.std.Queue
import weaver.GlobalRead

import scala.concurrent.duration.DurationInt

class KeyValueSpec(global: GlobalRead) extends JetStreamSpec(global) {

  testResource("basic KV operations") { ctx =>
    for {
      (_, kv, _) <- setupKeyValueBucket(ctx)

      // Put and get
      _      <- kv.put("test-key", "test-value".getBytes()).toResource
      entry1 <- kv.get("test-key").toResource

      // Update increases revision
      rev1   <- kv.put("key1", "value1".getBytes()).toResource
      rev2   <- kv.put("key1", "value2".getBytes()).toResource
      rev3   <- kv.put("key1", "value3".getBytes()).toResource
      entry2 <- kv.get("key1").toResource

      // Create enforces uniqueness
      _      <- kv.create("unique-key", "value1".getBytes()).toResource
      failed <- kv.create("unique-key", "value2".getBytes()).attempt.toResource

      // Conditional update with revision check
      revA         <- kv.put("keyA", "valueA1".getBytes()).toResource
      revB         <- kv.update("keyA", "valueA2".getBytes(), revA).toResource
      failedUpdate <- kv.update("keyA", "valueA3".getBytes(), revA).attempt.toResource

    } yield
    // Put and get assertions
    whenSuccess(entry1) { e =>
      expect.eql(new String(e.getValue), "test-value") &&
      expect.eql(e.getKey, "test-key") &&
      expect.eql(e.getRevision, 1L)
    } &&
      // Update revision assertions
      expect.eql(rev2, rev1 + 1L) &&
      expect.eql(rev3, rev1 + 2L) &&
      whenSuccess(entry2) { e =>
        expect.eql(e.getRevision, rev3) &&
        expect.eql(new String(e.getValue), "value3")
      } &&
      // Create uniqueness assertions
      expect(failed.isLeft) &&
      // Conditional update assertions
      expect.eql(revB, revA + 1L) &&
      expect(failedUpdate.isLeft)
  }

  testResource("delete and purge operations") { ctx =>
    for {
      (_, kv, _) <- setupKeyValueBucket(ctx)

      // Delete operation
      _      <- kv.put("key1", "value1".getBytes()).toResource
      _      <- kv.delete("key1").toResource
      entry1 <- kv.get("key1").toResource

      // Purge operation
      _      <- kv.put("key2", "value2".getBytes()).toResource
      _      <- kv.purge("key2").toResource
      entry2 <- kv.get("key2").toResource

    } yield expect(entry1.isEmpty) &&
      expect(entry2.isEmpty)
  }

  testResource("history and specific revisions") { ctx =>
    for {
      (_, kv, _) <- setupKeyValueBucket(ctx)

      _ <- kv.put("key1", "value1".getBytes()).toResource
      _ <- kv.put("key1", "value2".getBytes()).toResource
      _ <- kv.put("key1", "value3".getBytes()).toResource

      history <- kv.history("key1", 5.seconds).toResource

      entry1 <- kv.get("key1", 1).toResource
      entry2 <- kv.get("key1", 2).toResource

    } yield {
      val values = history.flatMap(e => Option(e.getValue).map(new String(_)))
      // History contains all operations
      expect.eql(history.length >= 3, true) &&
      expect(values.contains("value1")) &&
      expect(values.contains("value2")) &&
      expect(values.contains("value3")) &&
      // Specific revision retrieval
      whenSuccess(entry1) { e =>
        expect.eql(new String(e.getValue), "value1") &&
        expect.eql(e.getRevision, 1L)
      } &&
      whenSuccess(entry2) { e =>
        expect.eql(new String(e.getValue), "value2") &&
        expect.eql(e.getRevision, 2L)
      }
    }
  }

  testResource("list keys with filtering") { ctx =>
    for {
      (_, kv, _) <- setupKeyValueBucket(ctx)

      _ <- kv.put("orders.1", "value1".getBytes()).toResource
      _ <- kv.put("orders.2", "value2".getBytes()).toResource
      _ <- kv.put("events.1", "value3".getBytes()).toResource

      allKeys   <- kv.keys(5.seconds).toResource
      orderKeys <- kv.keys("orders.>", 5.seconds).toResource

    } yield expect.eql(allKeys.size, 3) &&
      expect(allKeys.contains("orders.1")) &&
      expect(allKeys.contains("orders.2")) &&
      expect(allKeys.contains("events.1")) &&
      expect.eql(orderKeys.size, 2) &&
      expect(orderKeys.contains("orders.1")) &&
      expect(orderKeys.contains("orders.2")) &&
      expect(!orderKeys.contains("events.1"))
  }

  testResource("watch for KV changes") { ctx =>
    for {
      (_, kv, _) <- setupKeyValueBucket(ctx)

      queue <- Queue.bounded[IO, io.nats.client.api.KeyValueEntry](10).toResource

      _ <- kv.put("config.host", "localhost".getBytes()).toResource

      _ <- kv.watch(
        keys = List("config.*"),
        watchMode = KvWatchMode.LatestValues,
        handler = entry => queue.offer(entry),
        warmupTimeout = 1.second
      )

      _ <- kv.put("config.port", "8080".getBytes()).toResource
      _ <- kv.put("other.key", "ignored".getBytes()).toResource     // Should not appear in watch
      _ <- kv.put("config.host", "127.0.0.1".getBytes()).toResource // Update

      // Collect watch events
      entry1 <- queue.take.toResource
      entry2 <- queue.take.toResource
      entry3 <- queue.take.toResource

    } yield
    // Should receive events for config.* keys only
    expect.eql(entry1.getKey, "config.host") &&
      expect.eql(new String(entry1.getValue), "localhost") &&
      expect.eql(entry2.getKey, "config.port") &&
      expect.eql(new String(entry2.getValue), "8080") &&
      expect.eql(entry3.getKey, "config.host") &&
      expect.eql(new String(entry3.getValue), "127.0.0.1")
  }
}
