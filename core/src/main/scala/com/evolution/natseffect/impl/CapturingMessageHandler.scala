package com.evolution.natseffect.impl

import io.nats.client.MessageHandler

/** A MessageHandler that, when invoked by CatsBasedDispatcherFactory, captures its message-handling
  * effect instead of dispatching it fire-and-forget, so the factory can run the effect with error
  * reporting and pending-message accounting attached.
  *
  * <p>This is needed when jnats wraps the handler in internal layers (e.g. the JetStream handler
  * performing status handling, repull pacing and ordered-consumer checks) that must keep running but
  * hide the handler from the factory, so the factory cannot obtain the effect directly and instead
  * invokes the outermost wrapper and collects the captured effect afterwards via
  * [[takeCapturedEffect]]. The handler is registered with the factory through
  * [[ConfiguringMessageHandler]]; invocation and collection happen within a single task on the
  * dispatcher's CE dispatcher.
  */
trait CapturingMessageHandler[F[_]] extends MessageHandler {

  /** Returns and clears the effect captured by the most recent [[onMessage]] invocation, if any
    * (e.g. none when jnats consumed the message internally without reaching this handler).
    */
  def takeCapturedEffect(): Option[F[Unit]]
}
