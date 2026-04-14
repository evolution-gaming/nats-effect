package com.evolution.natseffect.impl

import cats.effect.{Async, Resource}
import cats.syntax.functor.*
import com.evolution.natseffect.*
import io.nats.client.Connection.Status
import io.nats.client.api.ServerInfo
import io.nats.client.impl.Headers
import io.nats.client.ForceReconnectOptions

import java.net.InetAddress
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.jdk.CollectionConverters.CollectionHasAsScala
import scala.jdk.DurationConverters.*
import java.time.Duration as JDuration

private[natseffect] class WrappedConnection[F[_]: Async](
  wrapped: JConnection,
  override val options: Options[F]
) extends Connection[F]
    with JavaWrapper[JConnection] {

  override def publish(subject: String, body: Array[Byte]): F[Unit] =
    Async[F].delay(wrapped.publish(subject, body))

  override def publish(subject: String, headers: Headers, body: Array[Byte]): F[Unit] =
    Async[F].delay(wrapped.publish(subject, headers, body))

  override def publish(subject: String, replyTo: String, body: Array[Byte]): F[Unit] =
    Async[F].delay(wrapped.publish(subject, replyTo, body))

  override def publish(subject: String, replyTo: String, headers: Headers, body: Array[Byte]): F[Unit] =
    Async[F].delay(wrapped.publish(subject, replyTo, headers, body))

  override def publish(message: Message): F[Unit] =
    Async[F].delay(wrapped.publish(message.asJava))

  override def request(subject: String, body: Array[Byte]): F[Message] =
    wrapped.request(subject, body).toF[F].map(new WrappedMessage(_))

  override def request(subject: String, headers: Headers, body: Array[Byte]): F[Message] =
    wrapped.request(subject, headers, body).toF.map(new WrappedMessage(_))

  override def requestWithTimeout(subject: String, body: Array[Byte], timeout: FiniteDuration): F[Message] =
    wrapped.requestWithTimeout(subject, body, timeout.toJava).toF.map(new WrappedMessage(_))

  override def requestWithTimeout(subject: String, headers: Headers, body: Array[Byte], timeout: FiniteDuration): F[Message] =
    wrapped.requestWithTimeout(subject, headers, body, timeout.toJava).toF.map(new WrappedMessage(_))

  override def request(message: Message): F[Message] =
    wrapped.request(message.asJava).toF[F].map(new WrappedMessage(_))

  override def requestWithTimeout(message: Message, timeout: FiniteDuration): F[Message] =
    wrapped.requestWithTimeout(message.asJava, timeout.toJava).toF[F].map(new WrappedMessage(_))

  override def createDispatcher(): Resource[F, Dispatcher[F]] = WrappedDispatcher.make(wrapped)

  override def flush(timeout: Duration): F[Unit] = {
    val javaTimeout = timeout match {
      case _: Duration.Infinite     => JDuration.ZERO
      case duration: FiniteDuration => duration.toJava
    }
    Async[F].blocking(wrapped.flush(javaTimeout))
  }

  override def status: F[Status] = Async[F].delay(wrapped.getStatus)

  override def maxPayload: F[Long] = Async[F].delay(wrapped.getMaxPayload)

  override def servers: F[Seq[String]] = Async[F].delay(wrapped.getServers.asScala.toVector)

  override def statistics: F[Statistics[F]] = Async[F].delay(wrapped.getStatistics).map {
    case collector: UnwrappedStatisticsCollector[?] => collector.statisticsCollector.asInstanceOf[Statistics[F]]
    case other                                      => new WrappedStatistics[F](other)
  }

  override def serverInfo: F[Option[ServerInfo]] = Async[F].delay(Option(wrapped.getServerInfo))

  override def connectionUrl: F[Option[String]] = Async[F].delay(Option(wrapped.getConnectedUrl))

  override def clientInetAddress: F[Option[InetAddress]] = Async[F].delay(Option(wrapped.getClientInetAddress))

  override def lastError: F[Option[String]] = Async[F].delay(Option(wrapped.getLastError))

  override def clearLastError: F[Unit] = Async[F].delay(wrapped.clearLastError())

  override def createInbox: F[String] = Async[F].delay(wrapped.createInbox())

  override def flushBuffer: F[Unit] = Async[F].blocking(wrapped.flushBuffer())

  override def forceReconnect: F[Unit] = Async[F].blocking(wrapped.forceReconnect())

  override def forceReconnect(options: ForceReconnectOptions): F[Unit] = Async[F].blocking(wrapped.forceReconnect(options))

  override def RTT: F[FiniteDuration] = Async[F].blocking(wrapped.RTT().toScala)

  override def outgoingPendingMessageCount: F[Long] = Async[F].delay(wrapped.outgoingPendingMessageCount())

  override def outgoingPendingBytes: F[Long] = Async[F].delay(wrapped.outgoingPendingBytes())

  override def toString: String = s"Connection(uri=${options.natsServerUris.mkString(", ")}, " +
    s"serverInfo=${Option(wrapped.getServerInfo).map(_.toString).getOrElse("<not connected>")})"

  override def asJava: JConnection = wrapped
}
