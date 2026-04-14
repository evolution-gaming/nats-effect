package com.evolution.natseffect.jetstream

import cats.effect.{IO, Resource}
import com.evolution.natseffect.Nats
import io.nats.client.api.{KeyValueConfiguration, StorageType, StreamConfiguration}
import weaver.{Expectations, GlobalRead, IOSuite, TestName}

import scala.util.Random

abstract class JetStreamSpec(global: GlobalRead) extends IOSuite {

  override type Res = berlin.yuna.natsserver.logic.Nats

  type SubjectName = String

  type StreamName = String

  type BucketName = String

  override def sharedResource: Resource[IO, Res] = SharedJetStreamResources.get(global)

  protected def testResource(name: TestName)(run: Res => Resource[IO, Expectations]): Unit =
    test(name) { (res: Res) =>
      for {
        t           <- run(res).allocated
        (res, close) = t
        _           <- close
      } yield res
    }

  protected def randomSubject: IO[SubjectName] = IO(s"Subject${Random.nextInt().abs}")

  protected def randomStreamName: IO[StreamName] = IO(s"Stream${Random.nextInt().abs}")

  protected def randomBucketName: IO[BucketName] = IO(s"Bucket${Random.nextInt().abs}")

  protected def randomConsumerName: IO[String] = IO(s"Consumer${Random.nextInt().abs}")

  protected def setupStream(ctx: Res): Resource[IO, (JetStream[IO], StreamName, SubjectName)] = for {
    streamName <- randomStreamName.toResource
    subject    <- randomSubject.toResource
    connection <- Nats.connect(ctx.url())
    js         <- JetStream.fromConnection[IO](connection).toResource
    jsm        <- js.jetStreamManagement().toResource

    _ <- Resource.make(
      jsm.addStream(StreamConfiguration.builder().name(streamName).subjects(subject).build())
    )(_ => jsm.deleteStream(streamName).void)
  } yield (js, streamName, subject)

  protected def setupKeyValueBucket(ctx: Res): Resource[IO, (JetStream[IO], KeyValue[IO], BucketName)] =
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
            .maxHistoryPerKey(10)
            .build()
        )
      )(_ => kvm.delete(bucketName).void)

      kv <- js.keyValue(bucketName).toResource

    } yield (js, kv, bucketName)
}
