# Nats Effect

Scala client for [NATS](https://nats.io/), based on the official [Java NATS Client](https://github.com/nats-io/nats.java).
The library provides an API that is close to the original client, but adapted to [Cats Effect](https://typelevel.org/cats-effect/) 3.

JetStream support is a work in progress; an experimental implementation is available in the `nats-effect-jetstream` module.

## Getting Started

Add the library to `build.sbt`:

```scala
libraryDependencies += "com.evolution" % "nats-effect-core" % "<version>"
```

Artifacts are published for Scala 2.13 and Scala 3.

For JetStream support, add the jetstream module:
```scala
libraryDependencies += "com.evolution" % "nats-effect-jetstream" % "<version>"
```

You can try to override the Java NATS client version, but compatibility is not guaranteed.

## Connecting

Connecting to the default URL nats://localhost:4222:
```scala
import com.evolution.natseffect._

for {
  connection <- Nats.connect[IO]() // Resource[IO, Connection[IO]]
  // Do something with the connection
} yield ()
```

You don't have to close the connection manually; it will be closed automatically when the resource is released. You can 
also specify a specific server URL like in the Java library:

```scala
import com.evolution.natseffect._

for {
  connection <- Nats.connect[IO]("nats://demo.nats.io:4222")
  // Do something with the connection
} yield ()
```

Or `Options`:
```scala
import com.evolution.natseffect._

val options = Options[IO]()
  .withNatsServerUris(Vector("nats://127.0.0.1:1222", "nats://127.0.0.1:1224"))


for {
  connection <- Nats.connect[IO](options)
  // Do something with the connection
} yield ()
```

## Sending Messages

You have to specify at least a `subject` and a `body` to publish a message:
```scala
import com.evolution.natseffect._

def sendMessage(connection: Connection[IO]): IO[Unit] =
  connection.publish("subject", "body".getBytes(StandardCharsets.UTF_8))
```

Additionally, headers and a reply subject can be added:
```scala
import com.evolution.natseffect._

def sendMessage(connection: Connection[IO], sessionId: String): IO[Unit] = {
  val headers = new Headers().add("session_id", sessionId)
  connection.publish("subject", "reply-to", headers, "body".getBytes(StandardCharsets.UTF_8))
}
```

## Receiving Messages

Unlike the Java library, all subscriptions here are asynchronous and backed by a `Dispatcher`.
A `Dispatcher` does not create a thread, but relies on Cats Effect concurrency and guarantees ordered message delivery 
for all underlying subscriptions. Dispatchers can be created from an active connection:

```scala
import com.evolution.natseffect._

for {
  connection <- Nats.connect[IO]()
  dispatcher <- connection.createDispatcher() // Resource[IO, Dispatcher[IO]]
  // Do something with dispatcher
} yield ()
```

When the `Dispatcher` resource is released, all underlying subscriptions are destroyed.

### Subscriptions

To create a subscription, you need a dispatcher. Then call the `subscribe` method and specify a subject and an optional [queue](https://docs.nats.io/using-nats/developer/receiving/queues). Here is an example:

```scala
import com.evolution.natseffect._

for {
  connection       <- Nats.connect[IO]()
  dispatcher       <- connection.createDispatcher()
  makeSubscription = dispatcher.subscribe("subject", Some("queue_name")) { msg =>
    IO.println(msg)
  }
  subscription     <- makeSubscription.toResource
} yield ()
```

Multiple subscriptions for the same subject are supported. 

### Unsubscribing

There are a few ways to unsubscribe from receiving messages. When the dispatcher resource is released, all underlying 
subscriptions are terminated. You can also unsubscribe your dispatcher from messages with a certain subject:

```scala
import com.evolution.natseffect._

for {
  connection       <- Nats.connect[IO]()
  dispatcher       <- connection.createDispatcher()
  makeSubscription = (name: String) => dispatcher.subscribe("subject") { msg =>
    IO.println(s"Subscription $name: $msg")
  }
  subscription1    <- makeSubscription("sub1").toResource
  subscription2    <- makeSubscription("sub2").toResource
  _                <- dispatcher.unsubscribe("subject").toResource
} yield ()
```

Please note that the subject name or pattern should be exactly the same as for the `subscribe` method. In the example above, 
neither `subscription1` nor `subscription2` will receive messages. To unsubscribe from a single subscription, 
call the `unsubscribe` method directly on the subscription:

```scala
import com.evolution.natseffect._

for {
  connection       <- Nats.connect[IO]()
  dispatcher       <- connection.createDispatcher()
  makeSubscription = (name: String) => dispatcher.subscribe("subject") { msg =>
    IO.println(s"Subscription $name: $msg")
  }
  subscription1    <- makeSubscription("sub1").toResource
  subscription2    <- makeSubscription("sub2").toResource
  _                <- subscription1.unsubscribe.toResource
} yield ()
```

Here `subscription2` will still be receiving messages. The `unsubscribe` method also supports ['Unsubscribing After N Messages'](Unsubscribing After N Messages)

### Request-response
NATS supports request-response by allowing you to specify a subject to which the response should be sent. 
Use one of the `request` or `requestWithTimeout` methods:

```scala
import com.evolution.natseffect._

for {
  connection <- Nats.connect[IO]()
  response   <- connection.request("subject", "body".getBytes(StandardCharsets.UTF_8)).toResource
} yield ()
```

The subject for reply is generated automatically unless it's explicitly specified in the outgoing message.

## JetStream

JetStream support is experimental and requires a NATS server with JetStream enabled.

### Create JetStream context

```scala
import com.evolution.natseffect._
import com.evolution.natseffect.jetstream._

for {
  natsConnection <- Nats.connect[IO]()
  // Build a JetStream context on top of the NATS connection.
  jetStream      <- JetStream.fromConnection[IO](natsConnection).toResource
} yield ()
```

### Stream management

```scala
import com.evolution.natseffect.jetstream._
import io.nats.client.api.StreamConfiguration

for {
  natsConnection      <- Nats.connect[IO]()
  jetStream           <- JetStream.fromConnection[IO](natsConnection).toResource
  jetStreamManagement <- jetStream.jetStreamManagement().toResource
  // Create a stream that stores messages for the "events.>" subject hierarchy.
  _ <- jetStreamManagement
    .addStream(StreamConfiguration.builder().name("events").subjects("events.>").build())
    .toResource
  // Delete when the stream is no longer needed.
  _ <- jetStreamManagement.deleteStream("events").toResource
} yield ()
```

### Publishing

```scala
import com.evolution.natseffect.jetstream._

for {
  natsConnection     <- Nats.connect[IO]()
  jetStream          <- JetStream.fromConnection[IO](natsConnection).toResource
  jetStreamPublisher <- jetStream.jetStreamPublisher().toResource
  // Publish and await JetStream acknowledgement.
  _ <- jetStreamPublisher.publish("events.user.created", "payload".getBytes()).toResource
} yield ()
```

### Consuming (durable consumer)

Durable consumers keep their state on the server and can be shared across multiple subscribers (work-queue pattern).

```scala
import com.evolution.natseffect.jetstream._
import io.nats.client.api.ConsumerConfiguration

for {
  natsConnection          <- Nats.connect[IO]()
  jetStream               <- JetStream.fromConnection[IO](natsConnection).toResource
  streamContext           <- jetStream.streamContext("events").toResource
  durableConsumerContext <- streamContext
    .createOrUpdateConsumer(ConsumerConfiguration.builder.durable("durable-consumer").build)
    .toResource
  // Consume with explicit acknowledgements.
  _ <- durableConsumerContext.consume(msg => msg.ack).toResource
} yield ()
```

### Consuming (ordered consumer)

Ordered consumers are ephemeral, single-subscriber consumers that guarantee strict ordering and reset automatically on gaps.

```scala
import com.evolution.natseffect.jetstream._
import io.nats.client.api.OrderedConsumerConfiguration

for {
  natsConnection        <- Nats.connect[IO]()
  jetStream             <- JetStream.fromConnection[IO](natsConnection).toResource
  streamContext         <- jetStream.streamContext("events").toResource
  orderedConsumerContext <- streamContext.createOrderedConsumer(new OrderedConsumerConfiguration()).toResource
  // Ordered consumers allow a single active subscription.
  _ <- orderedConsumerContext.consume(_ => IO.unit).toResource
} yield ()
```

### Warmup

Warmup waits until the consumer drains pending messages (or the timeout elapses) before you proceed.

```scala
import com.evolution.natseffect.jetstream._
import com.evolution.natseffect.jetstream.Warmup.WarmupConsumerContextOps
import io.nats.client.api.OrderedConsumerConfiguration
import scala.concurrent.duration.*

for {
  natsConnection           <- Nats.connect[IO]()
  jetStream                <- JetStream.fromConnection[IO](natsConnection).toResource
  streamContext            <- jetStream.streamContext("events").toResource
  orderedConsumerContext   <- streamContext.createOrderedConsumer(new OrderedConsumerConfiguration()).toResource
  subscriptionWithWarmup   <- orderedConsumerContext.consumeWithWarmup(msg => msg.ack, timeout = 5.seconds)
  // Wait until warmup completes before continuing.
  _                        <- subscriptionWithWarmup.warmupLatch.get.toResource
} yield ()
```

### Key-Value management

Use KeyValueManagement to create and delete buckets, and to inspect their configuration.

```scala
import com.evolution.natseffect.jetstream._
import io.nats.client.api.KeyValueConfiguration

for {
  natsConnection      <- Nats.connect[IO]()
  jetStream           <- JetStream.fromConnection[IO](natsConnection).toResource
  keyValueManagement  <- jetStream.keyValueManagement().toResource
  // Create a bucket for configuration values.
  _ <- keyValueManagement.create(KeyValueConfiguration.builder().name("configs").build()).toResource
  // Delete the bucket when no longer needed.
  _ <- keyValueManagement.delete("configs").toResource
} yield ()
```

### Key-Value usage

KeyValue provides simple put/get/delete operations against a bucket.

```scala
import com.evolution.natseffect.jetstream._

for {
  natsConnection <- Nats.connect[IO]()
  jetStream      <- JetStream.fromConnection[IO](natsConnection).toResource
  keyValueBucket <- jetStream.keyValue("configs").toResource
  // Store, read, and remove a key.
  _ <- keyValueBucket.put("service.timeout", "30s").toResource
  _ <- keyValueBucket.get("service.timeout").toResource
  _ <- keyValueBucket.delete("service.timeout").toResource
} yield ()
```

## Monitoring

### Error Listener

You can specify a custom `ErrorListener` when you establish a connection to process connection errors in a certain way:

```scala
import com.evolution.natseffect._

val errorListener = new ErrorListener[IO] {
  override def errorOccurred(conn: Connection[IO], error: String): IO[Unit] = ???
  override def exceptionOccurred(conn: Connection[IO], exp: Exception): IO[Unit] = ???
  override def slowConsumerDetected(conn: Connection[IO], consumer: Consumer[IO]): IO[Unit] = ???
  override def messageDiscarded(conn: Connection[IO], msg: Message): IO[Unit] = ???
  override def socketWriteTimeout(conn: Connection[IO]): IO[Unit] = ???
}

val options = Options[IO]().withErrorListener(Some(errorListener))
Nats.connect(options)
```

By default, an Error Listener is provided by the Java library. There is also `com.evolution.natseffect.impl.ErrorListenerLogger`, 
which logs all errors via [Cats Helper](https://github.com/evolution-gaming/cats-helper/tree/master)..
You have to add `nats-effect-logback` to `build.sbt`:
```scala
libraryDependencies += "com.evolution" % "nats-effect-logback" % "<version>"
```

### Receiving connection events
To monitor what happens with the connection you can implement a custom `ConnectionListener` and receive connection-related events:

```scala
import com.evolution.natseffect._
import io.nats.client.ConnectionListener.Events

val connectionListener = new ConnectionListener[IO] {
  override def connectionEvent(conn: Connection[IO], `type`: Events): IO[Unit] =
    IO.println(s"Connection $conn received event $`type`")
}

val options = Options[IO]().withConnectionListener(Some(connectionListener))
Nats.connect(options)
```

There is no default listener.

### Metrics
The library allows you to collect connection-related metrics via `Options.statisticsCollector`, which is `None` by default.
There is an [SMetrics](https://github.com/evolution-gaming/smetrics) integration implementation. To use add an extra 
dependency to `build.sbt`:
```scala
libraryDependencies += "com.evolution" % "nats-effect-metrics" % "<version>"
```
Then you can add it into your code:
```scala
import cats.effect.{IO, Resource}
import com.evolutiongaming.smetrics.CollectorRegistry
import com.evolution.natseffect._

def establishConnection(collectorRegistry: CollectorRegistry[IO]): Resource[IO, Connection[IO]] =
  for {
    statisticsCollectors <- SMetricsStatisticsCollector.multicluster(collectorRegistry)
    options = Options[IO]().withStatisticsCollector(Some(statisticsCollectors.forCluster("default")))
    connection <- Nats.connect(options)
  } yield connection
```

You can reuse the same `StatisticsCollector.Multicluster` instance for multiple NATS connections.
Different connections are differentiated by a cluster label, which you specify when calling the `forCluster` method.
