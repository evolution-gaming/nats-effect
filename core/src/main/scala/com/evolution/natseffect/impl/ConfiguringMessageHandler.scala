package com.evolution.natseffect.impl

import cats.effect.std.Dispatcher
import io.nats.client.MessageHandler

/** Message handler that is used as a default handler to pass parameters to CatsBasedDispatcher, due to the absence of an alternative way.
  *
  * @param customCeDispatcher
  *   CE Dispatcher to be used instead of the default one.
  * @param defaultMessageHandler
  *   Actual default message handler to be used if no custom handler is provided.
  * @param exclusiveDefaultHandler
  *   When true, the default handler receives every message of the dispatcher, bypassing handlers registered per subscription. For JetStream
  *   subscriptions the per-subscription handler is a jnats-internal wrapper; bypassing it hands the raw messages (statuses included) to the
  *   default handler. Used by the paced pull engine's buffered transport.
  */
final case class ConfiguringMessageHandler[F[_]](
  customCeDispatcher: Option[Dispatcher[F]] = None,
  defaultMessageHandler: Option[MessageHandler] = None,
  exclusiveDefaultHandler: Boolean = false
) extends MessageHandler {
  override def onMessage(msg: JMessage): Unit =
    defaultMessageHandler.foreach(_.onMessage(msg))
}
