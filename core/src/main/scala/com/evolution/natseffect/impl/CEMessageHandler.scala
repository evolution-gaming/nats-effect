package com.evolution.natseffect.impl

import cats.effect.std.Dispatcher
import com.evolution.natseffect.Message
import io.nats.client.MessageHandler

final case class CEMessageHandler[F[_]](dispatcher: Dispatcher[F], f: Message => F[Unit]) extends MessageHandler {

  def onMessageEffect(msg: JMessage): F[Unit] = f(new WrappedMessage(msg))

  override def onMessage(msg: JMessage): Unit = {
    dispatcher.unsafeToFuture(onMessageEffect(msg))
    ()
  }
}
