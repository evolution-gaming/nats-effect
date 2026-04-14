package com.evolution.natseffect.jetstream.impl

import cats.effect.implicits.effectResourceOps
import cats.effect.kernel.Ref
import cats.effect.std.{Queue, QueueSource}
import cats.effect.{Async, Resource}
import cats.implicits.*
import com.evolution.natseffect.impl.JavaWrapper
import com.evolution.natseffect.jetstream.*
import com.evolution.natseffect.jetstream.Warmup.WarmupConsumerContextOps
import io.nats.client.api.*
import io.nats.client.impl.JNatsKeyValueImplOps
import io.nats.client.support.NatsKeyValueUtil.getOperation
import io.nats.client.support.{NatsConstants, NatsKeyValueUtil}
import io.nats.client.{KeyValueOptions, MessageTtl}

import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*

private[natseffect] class WrappedKeyValue[F[_]: Async](
  wrapped: JKeyValue,
  jetStream: JetStream[F],
  keyValueOptions: Option[KeyValueOptions]
) extends KeyValue[F]
    with JavaWrapper[JKeyValue] {

  override def getStatus: F[KeyValueStatus] =
    Async[F].blocking(wrapped.getStatus)

  override def bucketName: String =
    wrapped.getBucketName

  override def get(key: String): F[Option[KeyValueEntry]] =
    Async[F].blocking(Option(wrapped.get(key)))

  override def get(key: String, revision: Long): F[Option[KeyValueEntry]] =
    Async[F].blocking(Option(wrapped.get(key, revision)))

  override def put(key: String, value: Array[Byte]): F[Long] =
    Async[F].blocking(wrapped.put(key, value))

  override def put(key: String, value: String): F[Long] =
    Async[F].blocking(wrapped.put(key, value))

  override def put(key: String, value: Number): F[Long] =
    Async[F].blocking(wrapped.put(key, value))

  override def create(key: String, value: Array[Byte]): F[Long] =
    Async[F].blocking(wrapped.create(key, value))

  override def create(key: String, value: Array[Byte], messageTtl: MessageTtl): F[Long] =
    Async[F].blocking(wrapped.create(key, value, messageTtl))

  override def update(key: String, value: Array[Byte], expectedRevision: Long): F[Long] =
    Async[F].blocking(wrapped.update(key, value, expectedRevision))

  override def update(key: String, value: String, expectedRevision: Long): F[Long] =
    Async[F].blocking(wrapped.update(key, value, expectedRevision))

  override def delete(key: String): F[Unit] =
    Async[F].blocking(wrapped.delete(key))

  override def delete(key: String, expectedRevision: Long): F[Unit] =
    Async[F].blocking(wrapped.delete(key, expectedRevision))

  override def purge(key: String): F[Unit] =
    Async[F].blocking(wrapped.purge(key))

  override def purge(key: String, expectedRevision: Long): F[Unit] =
    Async[F].blocking(wrapped.purge(key, expectedRevision))

  override def purge(key: String, messageTtl: MessageTtl): F[Unit] =
    Async[F].blocking(wrapped.purge(key, messageTtl))

  override def purge(key: String, expectedRevision: Long, messageTtl: MessageTtl): F[Unit] =
    Async[F].blocking(wrapped.purge(key, expectedRevision, messageTtl))

  override def purgeDeletes: F[Unit] =
    Async[F].blocking(wrapped.purgeDeletes())

  override def purgeDeletes(options: KeyValuePurgeOptions): F[Unit] =
    Async[F].blocking(wrapped.purgeDeletes(options))

  override def watchAll(
    watchMode: KvWatchMode,
    handler: KeyValueEntry => F[Unit],
    warmupTimeout: FiniteDuration,
    metaDataOnly: Boolean
  ): Resource[F, SubscriptionWithWarmup[F]] =
    watch(List(NatsConstants.GREATER_THAN), watchMode, handler, warmupTimeout, metaDataOnly)

  override def watch(
    keys: List[String],
    watchMode: KvWatchMode,
    handler: KeyValueEntry => F[Unit],
    warmupTimeout: FiniteDuration,
    metaDataOnly: Boolean
  ): Resource[F, SubscriptionWithWarmup[F]] = {

    val orderedConsumerConfig = new OrderedConsumerConfiguration()
      .headersOnly(metaDataOnly)

    val withWatchMode = watchMode match {
      case KvWatchMode.LatestValues =>
        orderedConsumerConfig.deliverPolicy(DeliverPolicy.LastPerSubject)
      case KvWatchMode.AllHistory =>
        orderedConsumerConfig.deliverPolicy(DeliverPolicy.All)
      case KvWatchMode.FromRevision(revision) =>
        orderedConsumerConfig
          .deliverPolicy(DeliverPolicy.ByStartSequence)
          .startSequence(revision)
    }

    for {
      occ <- prepareOrderedConsumer(keys, withWatchMode).toResource
      sub <- occ.consumeWithWarmup(jsMsg => handler(new KeyValueEntry(jsMsg.asJava)), warmupTimeout)
    } yield sub
  }

  override def keys(timeout: FiniteDuration): F[List[String]] =
    keys(List(NatsConstants.GREATER_THAN), timeout)

  override def keys(filter: String, timeout: FiniteDuration): F[List[String]] =
    keys(List(filter), timeout)

  override def keys(filters: List[String], timeout: FiniteDuration): F[List[String]] = for {
    occ <- prepareOrderedConsumer(
      filters,
      new OrderedConsumerConfiguration()
        .deliverPolicy(DeliverPolicy.LastPerSubject)
        .headersOnly(true)
    )

    keysRef <- Ref.of[F, List[String]](List.empty)

    runConsumer = for {
      sub <- occ.consumeWithWarmup(
        jsMsg => {
          val op = getOperation(jsMsg.headers.orNull)
          if (op eq KeyValueOperation.PUT)
            keysRef.update(new NatsKeyValueUtil.BucketAndKey(jsMsg.asJava).key :: _)
          else
            Async[F].unit
        },
        timeout
      )

    } yield sub.warmupLatch

    _    <- runConsumer.use(warmupLatch => warmupLatch.get)
    keys <- keysRef.get

  } yield keys

  override def consumeKeys(queueCapacity: Option[Int], timeout: FiniteDuration): Resource[F, QueueSource[F, Option[String]]] =
    consumeKeys(List(NatsConstants.GREATER_THAN), queueCapacity, timeout)

  override def consumeKeys(
    filter: String,
    queueCapacity: Option[Int],
    timeout: FiniteDuration
  ): Resource[F, QueueSource[F, Option[String]]] =
    consumeKeys(List(filter), queueCapacity, timeout)

  def consumeKeys(
    filters: List[String],
    queueCapacity: Option[Int],
    timeout: FiniteDuration
  ): Resource[F, QueueSource[F, Option[String]]] = for {
    occ <- prepareOrderedConsumer(
      filters,
      new OrderedConsumerConfiguration()
        .deliverPolicy(DeliverPolicy.LastPerSubject)
        .headersOnly(true)
    ).toResource

    queue <- Resource.eval {
      queueCapacity match {
        case Some(capacity) => Queue.bounded[F, Option[String]](capacity)
        case None           => Queue.unbounded[F, Option[String]]
      }
    }

    handler = (jsMsg: JetStreamMessage[F]) => {
      val op = getOperation(jsMsg.headers.orNull)
      if (op eq KeyValueOperation.PUT)
        queue.offer(Some(new NatsKeyValueUtil.BucketAndKey(jsMsg.asJava).key))
      else
        Async[F].unit
    }

    // Run the consumer, and release it either when warmup is complete, or when the outer resource is released
    consumerReleased <- Ref.of[F, Boolean](false).toResource
    (sub, releaseOnce) <- Resource
      .make {
        for {
          (sub, release) <- occ.consumeWithWarmup(handler, timeout).allocated
          releaseOnce = consumerReleased.modify {
            case true  => (true, Async[F].unit)
            case false => (true, release)
          }.flatten
        } yield (sub, releaseOnce)
      } { case (_, releaseOnce) => releaseOnce }

    _ <- Async[F].background(sub.warmupLatch.get >> queue.offer(None) >> releaseOnce)

  } yield queue

  override def history(key: String, timeout: FiniteDuration): F[List[KeyValueEntry]] = for {
    occ <- prepareOrderedConsumer(
      List(key),
      new OrderedConsumerConfiguration()
        .deliverPolicy(DeliverPolicy.All)
    )

    entriesRef <- Ref.of[F, List[KeyValueEntry]](List.empty)

    runConsumer = occ
      .consumeWithWarmup(
        jsMsg => {
          val op = getOperation(jsMsg.headers.orNull)
          if (op eq KeyValueOperation.PUT) {
            entriesRef.update(new KeyValueEntry(jsMsg.asJava) :: _)
          } else {
            Async[F].unit
          }
        },
        timeout
      )
      .map(_.warmupLatch)

    _       <- runConsumer.use(warmupLatch => warmupLatch.get)
    entries <- entriesRef.get
  } yield entries.reverse

  private def prepareOrderedConsumer(filters: List[String], occ: OrderedConsumerConfiguration): F[OrderedConsumerContext[F]] = for {
    streamName     <- wrapped.getStreamName
    streamCtx      <- jetStream.streamContext(streamName, keyValueOptions.map(_.getJetStreamOptions))
    readSubjectF    = wrapped.readSubject[F](_)
    filterSubjects <- filters.traverse(readSubjectF)
    ocCtx          <- streamCtx.createOrderedConsumer(occ.filterSubjects(filterSubjects.asJava))
  } yield ocCtx

  override def asJava: JKeyValue = wrapped

}
