package com.evolution.natseffect.jetstream.impl

import cats.effect.Async
import com.evolution.natseffect.impl.JavaWrapper
import com.evolution.natseffect.jetstream.{ConsumerInfo, JetStreamSubscription}

private[natseffect] class WrappedJetStreamSubscription[F[_]: Async](
  wrapped: JJetStreamSubscription
) extends JetStreamSubscription[F]
    with JavaWrapper[JJetStreamSubscription] {

  override def getConsumerName: F[String] =
    Async[F].delay(wrapped.getConsumerName)

  override def getStreamName: F[String] =
    Async[F].delay(wrapped.getStreamName)

  override def getConsumerInfo: F[Option[ConsumerInfo]] =
    Async[F].blocking(Option(wrapped.getConsumerInfo).map(new WrappedConsumerInfo(_)))

  override def asJava: JJetStreamSubscription = wrapped
}
