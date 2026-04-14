package com.evolution.natseffect.impl

import com.evolution.natseffect.{Connection, Consumer, ErrorListener, Message}
import com.evolutiongaming.catshelper.Log

class ErrorListenerLogger[F[_]](log: Log[F]) extends ErrorListener[F] {

  override def errorOccurred(conn: Connection[F], error: String): F[Unit] =
    log.error(s"Error: $error. Connection: $conn")

  override def exceptionOccurred(conn: Connection[F], exp: Exception): F[Unit] =
    log.error(s"Exception raised for connection: $conn", exp)

  override def slowConsumerDetected(conn: Connection[F], consumer: Consumer[F]): F[Unit] =
    log.warn(s"Slow consumer detected. Connection: $conn. Consumer: $consumer")

  override def messageDiscarded(conn: Connection[F], msg: Message): F[Unit] =
    log.info(s"Message discarded. Message: $msg. Connection: $conn")

  override def socketWriteTimeout(conn: Connection[F]): F[Unit] =
    log.info(s"Socket write timeout. Connection: $conn")
}
