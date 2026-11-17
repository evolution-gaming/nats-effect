package com.evolution.natseffect.impl

import cats.effect.Async
import cats.effect.std.Dispatcher
import com.evolution.natseffect.{ErrorListener, Options}

private[natseffect] class UnwrappedErrorListener[F[_]: Async](
  errorListener: ErrorListener[F],
  dispatcher: Dispatcher[F],
  options: Options[F]
) extends JErrorListener {

  override def errorOccurred(conn: JConnection, error: String): Unit = {
    val wrappedConnection = new WrappedConnection[F](conn, options)
    dispatcher.unsafeRunSyncNoForkVoid(errorListener.errorOccurred(wrappedConnection, error))
  }

  override def exceptionOccurred(conn: JConnection, exp: Exception): Unit = {
    val wrappedConnection = new WrappedConnection[F](conn, options)
    dispatcher.unsafeRunSyncNoForkVoid(errorListener.exceptionOccurred(wrappedConnection, exp))
  }

  override def slowConsumerDetected(conn: JConnection, consumer: JConsumer): Unit = {
    val wrappedConnection = new WrappedConnection[F](conn, options)
    val wrappedConsumer   = new WrappedConsumer(consumer)
    dispatcher.unsafeRunSyncNoForkVoid(errorListener.slowConsumerDetected(wrappedConnection, wrappedConsumer))
  }

  override def messageDiscarded(conn: JConnection, msg: JMessage): Unit = {
    val wrappedMessage    = new WrappedMessage(msg)
    val wrappedConnection = new WrappedConnection[F](conn, options)
    dispatcher.unsafeRunSyncNoForkVoid(errorListener.messageDiscarded(wrappedConnection, wrappedMessage))
  }

  override def socketWriteTimeout(conn: JConnection): Unit = {
    val wrappedConnection = new WrappedConnection[F](conn, options)
    dispatcher.unsafeRunSyncNoForkVoid(errorListener.socketWriteTimeout(wrappedConnection))
  }
}
