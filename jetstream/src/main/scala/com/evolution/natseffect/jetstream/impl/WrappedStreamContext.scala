package com.evolution.natseffect.jetstream.impl

import cats.effect.Async
import cats.implicits.toFunctorOps
import com.evolution.natseffect.impl.{JConnection, JavaWrapper}
import com.evolution.natseffect.jetstream.{ConsumerContext, ConsumerInfo, OrderedConsumerContext, StreamContext, StreamInfo}
import io.nats.client.PurgeOptions
import io.nats.client.api.{ConsumerConfiguration, MessageInfo, OrderedConsumerConfiguration, PurgeResponse, StreamInfoOptions}

import scala.jdk.CollectionConverters.*

private[natseffect] class WrappedStreamContext[F[_]: Async](
  wrapped: JStreamContext,
  connection: JConnection
) extends StreamContext[F]
    with JavaWrapper[JStreamContext] {

  override def getStreamName: String = wrapped.getStreamName

  override def getStreamInfo(options: Option[StreamInfoOptions]): F[StreamInfo] =
    Async[F].blocking(new WrappedStreamInfo(wrapped.getStreamInfo(options.orNull)))

  override def purge(options: Option[PurgeOptions]): F[PurgeResponse] =
    Async[F].blocking(wrapped.purge(options.orNull))

  override def getConsumerContext(consumerName: String): F[ConsumerContext[F]] =
    Async[F]
      .blocking(wrapped.getConsumerContext(consumerName))
      .map(new WrappedConsumerContext[F](_, connection))

  override def createOrUpdateConsumer(config: ConsumerConfiguration): F[ConsumerContext[F]] =
    Async[F]
      .blocking(wrapped.createOrUpdateConsumer(config))
      .map(new WrappedConsumerContext[F](_, connection))

  override def createOrderedConsumer(config: OrderedConsumerConfiguration): F[OrderedConsumerContext[F]] =
    Async[F]
      .delay(wrapped.createOrderedConsumer(config))
      .map(new WrappedOrderedConsumerContext[F](_, connection, config))

  override def deleteConsumer(consumerName: String): F[Boolean] =
    Async[F].blocking(wrapped.deleteConsumer(consumerName))

  override def getConsumerNames: F[Vector[String]] =
    Async[F]
      .blocking(wrapped.getConsumerNames)
      .map(_.asScala.toVector)

  override def getConsumers: F[Vector[ConsumerInfo]] =
    Async[F]
      .blocking(wrapped.getConsumers)
      .map(_.asScala.toVector.map(new WrappedConsumerInfo(_)))

  override def getMessage(seq: Long): F[MessageInfo] =
    Async[F].blocking(wrapped.getMessage(seq))

  override def getLastMessage(subject: String): F[MessageInfo] =
    Async[F].blocking(wrapped.getLastMessage(subject))

  override def getFirstMessage(subject: String): F[MessageInfo] =
    Async[F].blocking(wrapped.getFirstMessage(subject))

  override def getNextMessage(seq: Long, subject: String): F[MessageInfo] =
    Async[F].blocking(wrapped.getNextMessage(seq, subject))

  override def deleteMessage(seq: Long): F[Boolean] =
    Async[F].blocking(wrapped.deleteMessage(seq))

  override def deleteMessage(seq: Long, erase: Boolean): F[Boolean] =
    Async[F].blocking(wrapped.deleteMessage(seq, erase))

  override def asJava: JStreamContext = wrapped
}
