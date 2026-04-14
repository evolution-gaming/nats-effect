package com.evolution.natseffect.jetstream.impl

import cats.effect.kernel.Async
import cats.effect.std.Dispatcher
import com.evolution.natseffect.impl.JMessage
import com.evolution.natseffect.jetstream.JetStreamMessage
import io.nats.client.MessageHandler

final case class CEJetStreamMessageHandler[F[_]: Async](
  dispatcher: Dispatcher[F],
  f: JetStreamMessage[F] => F[Unit]
) extends MessageHandler {

  override def onMessage(msg: JMessage): Unit = {
    dispatcher.unsafeToFuture(f(new WrappedJetStreamMessage[F](msg)))
    ()
  }
}
