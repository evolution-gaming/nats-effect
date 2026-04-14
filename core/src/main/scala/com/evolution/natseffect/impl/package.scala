package com.evolution.natseffect

import cats.effect.std.Dispatcher
import cats.effect.{Async, Resource}
import cats.implicits.{catsSyntaxOptionId, none}
import io.nats.client.ConnectionListener.Events
import io.nats.client.Options.Builder
import io.nats.client.impl.CatsBasedDispatcherFactory

import java.lang
import java.util.concurrent.CompletableFuture
import scala.jdk.DurationConverters.ScalaDurationOps

package object impl {

  type JConnection          = io.nats.client.Connection
  type JConnectionListener  = io.nats.client.ConnectionListener
  type JErrorListener       = io.nats.client.ErrorListener
  type JConsumer            = io.nats.client.Consumer
  type JMessage             = io.nats.client.Message
  type JOptions             = io.nats.client.Options
  type JStatisticsCollector = io.nats.client.StatisticsCollector
  type JDispatcher          = io.nats.client.Dispatcher
  type JStatistics          = io.nats.client.Statistics
  type JSubscription        = io.nats.client.Subscription

  implicit private[natseffect] class CompletableFutureOps[T](cf: => CompletableFuture[T]) {
    def toF[F[_]: Async]: F[T] = Async[F].fromCompletableFuture(Async[F].delay(cf))
  }

  implicit private[natseffect] class ConnectionListenerOps[F[_]](connectionListener: ConnectionListener[F]) {
    def asJava(options: Options[F])(implicit F: Async[F]): Resource[F, JConnectionListener] =
      Dispatcher.sequential.map { dispatcher =>
        new JConnectionListener {
          override def connectionEvent(conn: JConnection, `type`: Events): Unit = throw new NotImplementedError()
          override def connectionEvent(conn: JConnection, `type`: Events, time: lang.Long, uriDetails: String): Unit =
            dispatcher.unsafeRunAndForget(
              connectionListener.connectionEvent(new WrappedConnection[F](conn, options), `type`, time, uriDetails)
            )
        }
      }
  }

  implicit private[natseffect] class ErrorListenerOps[F[_]](errorListener: ErrorListener[F]) {
    def asJava(options: Options[F])(implicit F: Async[F]): Resource[F, JErrorListener] =
      Dispatcher.parallel.map { dispatcher =>
        errorListener.asJava(dispatcher, options)
      }
  }

  implicit private[natseffect] class StatisticsCollectorOps[F[_]](statisticsCollector: StatisticsCollector[F]) {
    def asJava(implicit F: Async[F]): Resource[F, JStatisticsCollector] =
      for {
        dispatcher <- Dispatcher.parallel
      } yield new UnwrappedStatisticsCollector[F](statisticsCollector, dispatcher)
  }

  implicit private[natseffect] class OptionsOps[F[_]](options: Options[F]) {
    import options.*
    def asJava(implicit F: Async[F]): Resource[F, JOptions] =
      for {
        errorListener       <- errorListener.fold(Resource.pure[F, Option[JErrorListener]](none))(_.asJava(options).map(_.some))
        connectionListener  <- connectionListener.fold(Resource.pure[F, Option[JConnectionListener]](none))(_.asJava(options).map(_.some))
        statisticsCollector <- statisticsCollector.fold(Resource.pure[F, Option[JStatisticsCollector]](none))(_.asJava.map(_.some))
        dispatcherFactory   <- CatsBasedDispatcherFactory.make
      } yield {
        val builder = new Builder()
          .servers(natsServerUris.toArray)
          .connectionName(connectionName.orNull)
          .sslContext(sslContext.orNull)
          .sslContextFactory(sslContextFactory.orNull)
          .maxControlLine(maxControlLine)
          .maxReconnects(maxReconnects)
          .reconnectWait(reconnectWait.toJava)
          .reconnectJitter(reconnectJitter.toJava)
          .reconnectJitterTls(reconnectJitterTls.toJava)
          .connectionTimeout(connectionTimeout.toJava)
          .socketReadTimeoutMillis(socketReadTimeoutMillis)
          .socketWriteTimeout(socketWriteTimeout.toJava)
          .socketSoLinger(socketSoLinger)
          .pingInterval(pingInterval.toJava)
          .requestCleanupInterval(requestCleanupInterval.toJava)
          .maxPingsOut(maxPingsOut)
          .reconnectBufferSize(reconnectBufferSize)
          .userInfo(username.orNull, password.orNull)
          .token(token.orNull)
          .bufferSize(bufferSize)
          .clientSideLimitChecks(clientSideLimitChecks)
          .inboxPrefix(inboxPrefix)
          .maxMessagesInOutgoingQueue(maxMessagesInOutgoingQueue)
          // By default in the Java library this parameter is set to 'false'. It means that the sending thread will be
          // blocked if the outgoing queue is full. In the Scala library sending messages is performed in the cats-effect
          // compute threads, which must not be blocked.
          .discardMessagesWhenOutgoingQueueFull()
          .serverPool(serverPool.orNull)
          .authHandler(authHandler.orNull)
          .reconnectDelayHandler(reconnectDelayHandler.orNull)
          .errorListener(errorListener.orNull)
          .timeTraceLogger(timeTraceLogger.orNull)
          .connectionListener(connectionListener.orNull)
          .statisticsCollector(statisticsCollector.orNull)
          .dataPortType(dataPortType.orNull)
          .proxy(proxy.orNull)
          .keystorePath(keystore.orNull)
          .keystorePassword(keystorePassword.orNull)
          .truststorePath(truststore.orNull)
          .truststorePassword(truststorePassword.orNull)
          .tlsAlgorithm(tlsAlgorithm)
          .credentialPath(credentialPath.orNull)
          .dispatcherFactory(dispatcherFactory)
          .callbackThreadFactory(callbackThreadFactory.orNull)
          .connectThreadFactory(connectThreadFactory.orNull)
          .readListener(readListener.orNull)
          .sendBufferSize(sendBufferSize)
          .receiveBufferSize(receiveBufferSize)
          .subjectValidationType(subjectValidationType)
          .connectExecutor(connectExecutor.orNull)
          .callbackExecutor(callbackExecutor.orNull)

        if (noRandomize) builder.noRandomize()
        if (reportNoResponders) builder.reportNoResponders
        if (verbose) builder.verbose()
        if (pedantic) builder.pedantic()
        if (useOldRequestStyle) builder.oldRequestStyle()
        if (traceConnection) builder.traceConnection()
        if (noEcho) builder.noEcho()
        if (noHeaders) builder.noHeaders()
        if (noNoResponders) builder.noNoResponders()
        if (supportUTF8Subjects) builder.supportUTF8Subjects()
        if (ignoreDiscoveredServers) builder.ignoreDiscoveredServers()
        if (tlsFirst) builder.tlsFirst()
        if (useTimeoutException) builder.useTimeoutException()
        if (useDefaultTls) builder.secure()
        if (useTrustAllTls) builder.opentls()
        if (!forceFlushOnRequest) builder.dontForceFlushOnRequest()
        if (enableFastFallback) builder.enableFastFallback()

        builder.build()
      }
  }

}
