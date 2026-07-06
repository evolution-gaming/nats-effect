package com.evolution.natseffect.jetstream.impl

import cats.effect.kernel.Async
import cats.effect.std.Dispatcher
import com.evolution.natseffect.impl.{CapturingMessageHandler, JMessage}
import com.evolution.natseffect.jetstream.JetStreamMessage

final case class CEJetStreamMessageHandler[F[_]: Async](
  dispatcher: Dispatcher[F],
  f: JetStreamMessage[F] => F[Unit]
) extends CapturingMessageHandler[F] {

  // Written by onMessage and read by takeCapturedEffect within a single task on this consumer's
  // sequential CE dispatcher, which runs tasks one at a time; volatile only for visibility across
  // the dispatcher's threads
  @volatile private var captured: Option[F[Unit]] = None

  override def onMessage(msg: JMessage): Unit =
    captured = Some(f(new WrappedJetStreamMessage[F](msg)))

  override def takeCapturedEffect(): Option[F[Unit]] = {
    val effect = captured
    captured = None
    effect
  }
}
