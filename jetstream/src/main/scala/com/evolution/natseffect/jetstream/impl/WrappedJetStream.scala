package com.evolution.natseffect.jetstream.impl

import cats.effect.Async
import com.evolution.natseffect.Message
import com.evolution.natseffect.impl.{CompletableFutureOps, JavaWrapper}
import com.evolution.natseffect.jetstream.JetStreamPublisher
import io.nats.client.PublishOptions
import io.nats.client.api.PublishAck
import io.nats.client.impl.Headers

private[natseffect] class WrappedJetStream[F[_]: Async](
  wrapped: JJetStream
) extends JetStreamPublisher[F]
    with JavaWrapper[JJetStream] {

  override def publish(subject: String, body: Array[Byte]): F[PublishAck] =
    wrapped.publishAsync(subject, body).toF[F]

  override def publish(subject: String, headers: Headers, body: Array[Byte]): F[PublishAck] =
    wrapped.publishAsync(subject, headers, body).toF[F]

  override def publish(subject: String, body: Array[Byte], options: PublishOptions): F[PublishAck] =
    wrapped.publishAsync(subject, body, options).toF[F]

  override def publish(subject: String, headers: Headers, body: Array[Byte], options: PublishOptions): F[PublishAck] =
    wrapped.publishAsync(subject, headers, body, options).toF[F]

  override def publish(message: Message): F[PublishAck] =
    wrapped.publishAsync(message.asJava).toF[F]

  override def publish(message: Message, options: PublishOptions): F[PublishAck] =
    wrapped.publishAsync(message.asJava, options).toF[F]

  override def asJava: JJetStream = wrapped
}
