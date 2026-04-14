package com.evolution.natseffect.jetstream

import cats.effect.IO
import weaver.GlobalRead

import scala.concurrent.duration.*

class KeyValueReaderSpec(global: GlobalRead) extends JetStreamSpec(global) {

  def expectEventually(
    check: IO[weaver.Expectations]
  )(
    poll: FiniteDuration = 50.millis,
    timeout: FiniteDuration = 5.seconds
  ): IO[Unit] = {
    val deadline = IO.monotonic.map(_ + timeout)

    def loop(until: FiniteDuration): IO[Unit] =
      check.flatMap(_.failFast[IO]).attempt.flatMap {
        case Right(_) => IO.unit

        case Left(e) =>
          IO.monotonic.flatMap { now =>
            if (now >= until) IO.raiseError(e)
            else IO.sleep(poll) *> loop(until)
          }
      }

    deadline.flatMap(loop)
  }

  testResource("KeyValueReader with filter caches matching keys") { ctx =>
    for {
      (js, kv, bucketName) <- setupKeyValueBucket(ctx)

      // Create keys with different prefixes
      _ <- kv.put("config.db.host", "localhost".getBytes()).toResource
      _ <- kv.put("config.db.port", "5432".getBytes()).toResource
      _ <- kv.put("config.api.url", "http://api".getBytes()).toResource
      _ <- kv.put("feature.new-ui", "enabled".getBytes()).toResource
      _ <- kv.put("feature.beta", "disabled".getBytes()).toResource

      // Create reader with filter for config.db.* keys
      kvReader <- KeyValueReader.make[IO](
        js,
        bucketName,
        keyFilters = Set("config.db.*"),
        warmupTimeout = 5.seconds
      )

      // Check cached keys
      cachedKeys <- kvReader.keys.toResource

      // Get values from cache
      hostValue <- kvReader.get("config.db.host").toResource
      portValue <- kvReader.get("config.db.port").toResource
      apiValue  <- kvReader.get("config.api.url").toResource
      uiValue   <- kvReader.get("feature.new-ui").toResource

      // Update a cached key
      _ <- kv.put("config.db.host", "127.0.0.1".getBytes()).toResource

      // Cache updates with new values
      _ <- expectEventually(
        kvReader
          .get("config.db.host")
          .map(exists(_)(value => expect.eql("127.0.0.1", new String(value))))
      )(poll = 100.millis, timeout = 3.seconds).toResource

    } yield
    // Only filtered keys are cached
    expect.eql(cachedKeys, Set("config.db.host", "config.db.port")) &&
      // Cached values are retrievable
      expect.eql(hostValue.map(new String(_)), Some("localhost")) &&
      expect.eql(portValue.map(new String(_)), Some("5432")) &&
      // Non-filtered keys return None
      expect(apiValue.isEmpty) &&
      expect(uiValue.isEmpty)
  }

  testResource("KeyValueReader with empty filter caches all keys") { ctx =>
    for {
      (js, kv, bucketName) <- setupKeyValueBucket(ctx)

      _ <- kv.put("key1", "value1".getBytes()).toResource
      _ <- kv.put("key2", "value2".getBytes()).toResource
      _ <- kv.put("key3", "value3".getBytes()).toResource

      // Create reader with no filter (caches all keys)
      kvReader <- KeyValueReader.make[IO](
        js,
        bucketName,
        keyFilters = Set.empty,
        warmupTimeout = 5.seconds
      )

      cachedKeys <- kvReader.keys.toResource

    } yield expect.eql(cachedKeys, Set("key1", "key2", "key3"))
  }

  testResource("DynamicKeyValueReader updates filters dynamically") { ctx =>
    for {
      (js, kv, bucketName) <- setupKeyValueBucket(ctx)

      // Create keys with different prefixes
      _ <- kv.put("even.2", "value2".getBytes()).toResource
      _ <- kv.put("even.4", "value4".getBytes()).toResource
      _ <- kv.put("even.6", "value6".getBytes()).toResource
      _ <- kv.put("odd.1", "value1".getBytes()).toResource
      _ <- kv.put("odd.3", "value3".getBytes()).toResource
      _ <- kv.put("odd.5", "value5".getBytes()).toResource

      // Create dynamic reader with filter for even keys
      kvReader <- DynamicKeyValueReader.make[IO](
        js,
        bucketName,
        keyFilters = Set("even.*"),
        warmupTimeout = 5.seconds
      )

      // Check initial cached keys
      initialKeys <- kvReader.keys.toResource

      // Update filter to odd keys
      _ <- kvReader.updateFilters(_ => Set("odd.*")).toResource

      // Check updated cached keys
      updatedKeys <- kvReader.keys.toResource

      // Get values after filter update
      evenValue <- kvReader.get("even.2").toResource
      oddValue  <- kvReader.get("odd.1").toResource

    } yield
    // Initial state caches even keys
    expect.eql(initialKeys, Set("even.2", "even.4", "even.6")) &&
      // After update, caches odd keys
      expect.eql(updatedKeys, Set("odd.1", "odd.3", "odd.5")) &&
      // Even keys are no longer cached
      expect(evenValue.isEmpty) &&
      // Odd keys are now cached
      expect.eql(oddValue.map(new String(_)), Some("value1"))
  }
}
