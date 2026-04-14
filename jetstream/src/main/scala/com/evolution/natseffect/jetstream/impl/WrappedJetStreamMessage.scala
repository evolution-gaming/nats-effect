package com.evolution.natseffect.jetstream.impl

import cats.effect.Async
import com.evolution.natseffect.impl.{JMessage, JavaWrapper, WrappedMessage}
import com.evolution.natseffect.jetstream.JetStreamMessage
import io.nats.client.impl.{AckType, NatsJetStreamMetaData}

import scala.concurrent.duration.FiniteDuration
import scala.jdk.DurationConverters.ScalaDurationOps

private[natseffect] class WrappedJetStreamMessage[F[_]: Async](
  wrapped: JMessage
) extends WrappedMessage(wrapped)
    with JetStreamMessage[F]
    with JavaWrapper[JMessage] {

  override def metaData: F[NatsJetStreamMetaData] =
    Async[F].delay(wrapped.metaData())

  override def lastAck: F[AckType] =
    Async[F].delay(wrapped.lastAck())

  override def ackSync(timeout: FiniteDuration): F[Unit] =
    Async[F].blocking(wrapped.ackSync(timeout.toJava))

  // The following ack variant perform a simple publish via internal queue write, hence delay instead of blocking

  override def ack: F[Unit] =
    Async[F].delay(wrapped.ack())

  override def nak: F[Unit] =
    Async[F].delay(wrapped.nak())

  override def nakWithDelay(nakDelay: FiniteDuration): F[Unit] =
    Async[F].delay(wrapped.nakWithDelay(nakDelay.toJava))

  override def term: F[Unit] =
    Async[F].delay(wrapped.term())

  override def inProgress: F[Unit] =
    Async[F].delay(wrapped.inProgress())

  override def asJava: JMessage = wrapped
}
