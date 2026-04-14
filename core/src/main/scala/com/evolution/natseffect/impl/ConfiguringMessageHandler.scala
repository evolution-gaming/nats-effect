package com.evolution.natseffect.impl

import cats.effect.std.Dispatcher
import io.nats.client.MessageHandler

/** Message handler that is used as a default handler to pass parameters to CatsBasedDispatcher, due to the absence of an alternative way.
  *
  * @param customCeDispatcher
  *   CE Dispatcher to be used instead of the default one.
  * @param defaultMessageHandler
  *   Actual default message handler to be used if no custom handler is provided.
  */
final case class ConfiguringMessageHandler[F[_]](
  customCeDispatcher: Option[Dispatcher[F]] = None,
  defaultMessageHandler: Option[MessageHandler] = None
) extends MessageHandler {
  override def onMessage(msg: JMessage): Unit =
    defaultMessageHandler.foreach(_.onMessage(msg))
}
