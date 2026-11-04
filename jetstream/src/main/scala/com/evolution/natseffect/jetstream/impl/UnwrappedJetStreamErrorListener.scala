package com.evolution.natseffect.jetstream.impl

import cats.effect.Async
import com.evolution.natseffect.Options
import com.evolution.natseffect.impl.*
import com.evolution.natseffect.jetstream.JetStreamErrorListener
import io.nats.client.JetStreamSubscription
import io.nats.client.support.Status

private[natseffect] class UnwrappedJetStreamErrorListener[F[_]: Async](
  errorListener: JetStreamErrorListener[F],
  options: Options[F],
  effectRunner: EffectRunner[F]
) extends UnwrappedErrorListener[F](errorListener, options, effectRunner) {

  override def heartbeatAlarm(conn: JConnection, sub: JetStreamSubscription, lastStreamSequence: Long, lastConsumerSequence: Long): Unit = {
    val wrappedConnection            = new WrappedConnection[F](conn, options)
    val wrappedJetStreamSubscription = new WrappedJetStreamSubscription[F](sub)
    effectRunner.run(
      errorListener.heartbeatAlarm(wrappedConnection, wrappedJetStreamSubscription, lastStreamSequence, lastConsumerSequence)
    )
  }

  override def pullStatusWarning(conn: JConnection, sub: JetStreamSubscription, status: Status): Unit = {
    val wrappedConnection            = new WrappedConnection[F](conn, options)
    val wrappedJetStreamSubscription = new WrappedJetStreamSubscription[F](sub)
    effectRunner.run(errorListener.pullStatusWarning(wrappedConnection, wrappedJetStreamSubscription, status))
  }

  override def pullStatusError(conn: JConnection, sub: JetStreamSubscription, status: Status): Unit = {
    val wrappedConnection            = new WrappedConnection[F](conn, options)
    val wrappedJetStreamSubscription = new WrappedJetStreamSubscription[F](sub)
    effectRunner.run(errorListener.pullStatusError(wrappedConnection, wrappedJetStreamSubscription, status))
  }
}
