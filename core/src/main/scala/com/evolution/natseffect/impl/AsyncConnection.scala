package com.evolution.natseffect.impl

import cats.effect.{Async, Resource}
import cats.syntax.all.*
import com.evolution.natseffect.*
import io.nats.client.Connection.Status
import io.nats.client.ForceReconnectOptions
import io.nats.client.api.ServerInfo
import io.nats.client.impl.Headers

import java.net.InetAddress
import scala.concurrent.duration.{Duration, FiniteDuration}

/* A connection that is established in the background, every operation waits for it via `established` (which fails with
 * NotEstablishedException if it is not established in time) */
private[natseffect] class AsyncConnection[F[_]: Async](
  established: F[WrappedConnection[F]],
  override val options: Options[F]
) extends Connection[F] {

  private def withConnection[A](f: WrappedConnection[F] => F[A]): F[A] = established.flatMap(f)

  override def publish(subject: String, body: Array[Byte]): F[Unit] = withConnection(_.publish(subject, body))

  override def publish(subject: String, headers: Headers, body: Array[Byte]): F[Unit] = withConnection(_.publish(subject, headers, body))

  override def publish(subject: String, replyTo: String, body: Array[Byte]): F[Unit] = withConnection(_.publish(subject, replyTo, body))

  override def publish(subject: String, replyTo: String, headers: Headers, body: Array[Byte]): F[Unit] =
    withConnection(_.publish(subject, replyTo, headers, body))

  override def publish(message: Message): F[Unit] = withConnection(_.publish(message))

  override def request(subject: String, body: Array[Byte]): F[Message] = withConnection(_.request(subject, body))

  override def request(subject: String, headers: Headers, body: Array[Byte]): F[Message] =
    withConnection(_.request(subject, headers, body))

  override def requestWithTimeout(subject: String, body: Array[Byte], timeout: FiniteDuration): F[Message] =
    withConnection(_.requestWithTimeout(subject, body, timeout))

  override def requestWithTimeout(subject: String, headers: Headers, body: Array[Byte], timeout: FiniteDuration): F[Message] =
    withConnection(_.requestWithTimeout(subject, headers, body, timeout))

  override def request(message: Message): F[Message] = withConnection(_.request(message))

  override def requestWithTimeout(message: Message, timeout: FiniteDuration): F[Message] =
    withConnection(_.requestWithTimeout(message, timeout))

  override def createDispatcher(): Resource[F, Dispatcher[F]] = Resource.eval(established).flatMap(_.createDispatcher())

  override def flush(timeout: Duration): F[Unit] = withConnection(_.flush(timeout))

  override def status: F[Status] = withConnection(_.status)

  override def maxPayload: F[Long] = withConnection(_.maxPayload)

  override def servers: F[Seq[String]] = withConnection(_.servers)

  override def statistics: F[Statistics[F]] = withConnection(_.statistics)

  override def serverInfo: F[Option[ServerInfo]] = withConnection(_.serverInfo)

  override def connectionUrl: F[Option[String]] = withConnection(_.connectionUrl)

  override def clientInetAddress: F[Option[InetAddress]] = withConnection(_.clientInetAddress)

  override def lastError: F[Option[String]] = withConnection(_.lastError)

  override def clearLastError: F[Unit] = withConnection(_.clearLastError)

  override def createInbox: F[String] = withConnection(_.createInbox)

  override def flushBuffer: F[Unit] = withConnection(_.flushBuffer)

  override def forceReconnect: F[Unit] = withConnection(_.forceReconnect)

  override def forceReconnect(options: ForceReconnectOptions): F[Unit] = withConnection(_.forceReconnect(options))

  override def RTT: F[FiniteDuration] = withConnection(_.RTT)

  override def outgoingPendingMessageCount: F[Long] = withConnection(_.outgoingPendingMessageCount)

  override def outgoingPendingBytes: F[Long] = withConnection(_.outgoingPendingBytes)
}
