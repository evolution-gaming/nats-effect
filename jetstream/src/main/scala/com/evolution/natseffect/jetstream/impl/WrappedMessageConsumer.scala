package com.evolution.natseffect.jetstream.impl

import cats.effect.Async
import cats.implicits.toFunctorOps
import com.evolution.natseffect.impl.JavaWrapper
import com.evolution.natseffect.jetstream.{ConsumerInfo, MessageSubscription}

private[natseffect] class WrappedMessageConsumer[F[_]: Async](
  wrapped: JMessageConsumer
) extends MessageSubscription[F]
    with JavaWrapper[JMessageConsumer] {

  override def getConsumerName: F[String] =
    Async[F].delay(wrapped.getConsumerName)

  override def getConsumerInfo: F[ConsumerInfo] =
    Async[F].blocking(new WrappedConsumerInfo(wrapped.getConsumerInfo))

  override def getCachedConsumerInfo: F[Option[ConsumerInfo]] =
    Async[F].delay(wrapped.getCachedConsumerInfo).map(Option(_).map(new WrappedConsumerInfo(_)))

  override def stop: F[Unit] =
    Async[F].delay(wrapped.stop())

  override def close: F[Unit] =
    Async[F].delay(wrapped.close())

  override def isStopped: F[Boolean] =
    Async[F].delay(wrapped.isStopped)

  override def isFinished: F[Boolean] =
    Async[F].delay(wrapped.isFinished)

  override def asJava: JMessageConsumer = wrapped
}
