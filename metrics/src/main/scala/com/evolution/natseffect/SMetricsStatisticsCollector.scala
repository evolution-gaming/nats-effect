package com.evolution.natseffect

import cats.effect.implicits.{effectResourceOps, monadCancelOps_}
import cats.effect.std.MapRef
import cats.syntax.all.*
import cats.effect.{Async, Resource, Sync}
import com.evolutiongaming.smetrics.{CollectorRegistry, LabelNames, LabelValues, Quantiles, Summary}

/** StatisticCollector implementation, based on SMetrics (https://github.com/evolution-gaming/smetrics)
  */
object SMetricsStatisticsCollector {

  private trait GettableCounter[F[_]] {
    def inc(value: Long = 1L): F[Unit]
    def get: F[Long]
  }

  private trait GettableGauge[F[_]] {
    def inc(value: Long = 1L): F[Unit]
    def dec(value: Long = 1L): F[Unit]
    def get: F[Long]
  }

  implicit private class CollectorRegistryOps[F[_]: Sync](collectorRegistry: CollectorRegistry[F]) {
    def gettableCounter(name: String, help: String): Resource[F, LabelValues.`1`[GettableCounter[F]]] =
      for {
        counter <- collectorRegistry.counter(name, help, LabelNames("cluster"))
        value1  <- MapRef.ofConcurrentHashMap[F, String, Long]().toResource
        value    = MapRef.defaultedMapRef(value1, 0L)
      } yield { (label: String) =>
        new GettableCounter[F] {
          override def inc(newValue: Long): F[Unit] =
            (value(label).update(_ + newValue) *> counter.labels(label).inc(newValue.toDouble)).uncancelable
          override def get: F[Long] = value(label).get
        }
      }

    def gettableGauge(name: String, help: String): Resource[F, LabelValues.`1`[GettableGauge[F]]] =
      for {
        gauge  <- collectorRegistry.gauge(name, help, LabelNames("cluster"))
        value1 <- MapRef.ofConcurrentHashMap[F, String, Long]().toResource
        value   = MapRef.defaultedMapRef(value1, 0L)
      } yield { (label: String) =>
        new GettableGauge[F] {
          override def inc(newValue: Long): F[Unit] =
            (value(label).update(_ + newValue) *> gauge.labels(label).inc(newValue.toDouble)).uncancelable
          override def dec(newValue: Long): F[Unit] =
            (value(label).update(_ - newValue) *> gauge.labels(label).dec(newValue.toDouble)).uncancelable
          override def get: F[Long] = value(label).get
        }
      }
  }

  /** Create SMetrics-based `StatisticsCollector`. You can provide a created instance to `Options`.
    * @param collectorRegistry
    *   the registry, used to build metrics. Make sure that this call is performed only once for the provided instance or use a cached
    *   version of `CollectorRegistry`. If you need to initialize more than one NATS connection, use a
    *   `collectorRegistry.prefixed("connection1")`
    * @return
    *   a `Resource` which holds a new StatisticCollector
    */
  def make[F[_]: Async](collectorRegistry: CollectorRegistry[F]): Resource[F, StatisticsCollector[F]] =
    multicluster(collectorRegistry).map(_.forCluster("default"))

  def multicluster[F[_]: Async](collectorRegistry: CollectorRegistry[F]): Resource[F, StatisticsCollector.Multicluster[F]] =
    for {
      pingsCounter <- collectorRegistry.gettableCounter("nats_pings", "The total number of pings that have been sent from NATS connection")
      reconnectCounter <- collectorRegistry
        .gettableCounter("nats_reconnects", "The total number of times NATS connection has tried to reconnect")
      droppedCounter <- collectorRegistry
        .gettableCounter("nats_dropped", "The total number of messages dropped by NATS connection across all slow consumers")
      oksCounter <- collectorRegistry
        .gettableCounter("nats_oks", "The total number of +OKs received by NATS connection")
      errsCounter <- collectorRegistry
        .gettableCounter("errsCounter", "The total number of -ERRs received by NATS connection")
      exceptionsCounter <- collectorRegistry
        .gettableCounter("nats_exceptions", "The total number of exceptions seen by NATS connection")
      requestsSentCounter <- collectorRegistry
        .gettableCounter("nats_requests_sent", "The total number of requests sent by NATS connection")
      repliesReceivedCounter <- collectorRegistry
        .gettableCounter("nats_replies_received", "The total number of replies received by NATS connection")
      duplicateRepliesReceivedCounter <- collectorRegistry
        .gettableCounter("nats_duplicate_replies_received", "The total number of duplicate replies received by NATS connection")
      orphanRepliesReceivedCounter <- collectorRegistry
        .gettableCounter("nats_orphan_replies_received", "The total number of orphan replies received by NATS connection")
      inMsgsCounter <- collectorRegistry
        .gettableCounter("nats_in_msgs", "The total number of messages that have come in to NATS connection")
      outMsgsCounter <- collectorRegistry
        .gettableCounter("nats_out_msgs", "The total number of messages that have gone out of NATS connection")
      inBytesCounter <- collectorRegistry
        .gettableCounter("nats_in_bytes", "The total number of message bytes that have come in to NATS connection")
      outBytesCounter <- collectorRegistry
        .gettableCounter("nats_out_bytes", "The total number of message bytes that have gone out of NATS connection")
      flushCounterF <- collectorRegistry
        .gettableCounter("nats_flush", "The total number of outgoing message flushes by NATS connection")
      outstandingRequestsGauge <- collectorRegistry
        .gettableGauge("nats_outstanding_requests", "The count of outstanding requests from NATS connection")
      readSummary <- collectorRegistry.summary(
        "nats_read",
        "The socket readings by NATS connection",
        Quantiles.Default,
        LabelNames("cluster")
      )
      writeSummary <- collectorRegistry.summary(
        "nats_write",
        "The socket writings by NATS connection",
        Quantiles.Default,
        LabelNames("cluster")
      )
    } yield { (cluster: String) =>
      new Impl[F](
        incrementPingCount = pingsCounter.labels(cluster).inc(),
        incrementReconnects = reconnectCounter.labels(cluster).inc(),
        incrementDroppedCount = droppedCounter.labels(cluster).inc(),
        incrementOkCount = oksCounter.labels(cluster).inc(),
        incrementErrCount = errsCounter.labels(cluster).inc(),
        incrementExceptionCount = exceptionsCounter.labels(cluster).inc(),
        incrementRequestsSent = requestsSentCounter.labels(cluster).inc(),
        incrementRepliesReceived = repliesReceivedCounter.labels(cluster).inc(),
        incrementDuplicateRepliesReceived = duplicateRepliesReceivedCounter.labels(cluster).inc(),
        incrementOrphanRepliesReceived = orphanRepliesReceivedCounter.labels(cluster).inc(),
        incrementInMsgs = inMsgsCounter.labels(cluster).inc(),
        incrementOutMsgs = outMsgsCounter.labels(cluster).inc(),
        incrementInBytes = inBytesCounter.labels(cluster),
        incrementOutBytes = outBytesCounter.labels(cluster),
        incrementFlushCounter = flushCounterF.labels(cluster).inc(),
        incrementOutstandingRequests = outstandingRequestsGauge.labels(cluster).inc(),
        decrementOutstandingRequests = outstandingRequestsGauge.labels(cluster).dec(),
        registerRead = readSummary.labels(cluster),
        registerWrite = writeSummary.labels(cluster),
        pings = pingsCounter.labels(cluster).get,
        reconnects = reconnectCounter.labels(cluster).get,
        droppedCount = droppedCounter.labels(cluster).get,
        OKs = oksCounter.labels(cluster).get,
        errs = errsCounter.labels(cluster).get,
        exceptions = exceptionsCounter.labels(cluster).get,
        requestsSent = requestsSentCounter.labels(cluster).get,
        repliesReceived = repliesReceivedCounter.labels(cluster).get,
        duplicateRepliesReceived = duplicateRepliesReceivedCounter.labels(cluster).get,
        orphanRepliesReceived = orphanRepliesReceivedCounter.labels(cluster).get,
        inMsgs = inMsgsCounter.labels(cluster).get,
        outMsgs = outMsgsCounter.labels(cluster).get,
        inBytes = inBytesCounter.labels(cluster).get,
        outBytes = outBytesCounter.labels(cluster).get,
        flushCounter = flushCounterF.labels(cluster).get,
        outstandingRequests = outstandingRequestsGauge.labels(cluster).get
      )
    }

  final private class Impl[F[_]](
    val incrementPingCount: F[Unit],
    val incrementReconnects: F[Unit],
    val incrementDroppedCount: F[Unit],
    val incrementOkCount: F[Unit],
    val incrementErrCount: F[Unit],
    val incrementExceptionCount: F[Unit],
    val incrementRequestsSent: F[Unit],
    val incrementRepliesReceived: F[Unit],
    val incrementDuplicateRepliesReceived: F[Unit],
    val incrementOrphanRepliesReceived: F[Unit],
    val incrementInMsgs: F[Unit],
    val incrementOutMsgs: F[Unit],
    incrementInBytes: GettableCounter[F],
    incrementOutBytes: GettableCounter[F],
    val incrementFlushCounter: F[Unit],
    val incrementOutstandingRequests: F[Unit],
    val decrementOutstandingRequests: F[Unit],
    registerRead: Summary[F],
    registerWrite: Summary[F],
    val pings: F[Long],
    val reconnects: F[Long],
    val droppedCount: F[Long],
    val OKs: F[Long],
    val errs: F[Long],
    val exceptions: F[Long],
    val requestsSent: F[Long],
    val repliesReceived: F[Long],
    val duplicateRepliesReceived: F[Long],
    val orphanRepliesReceived: F[Long],
    val inMsgs: F[Long],
    val outMsgs: F[Long],
    val inBytes: F[Long],
    val outBytes: F[Long],
    val flushCounter: F[Long],
    val outstandingRequests: F[Long]
  ) extends StatisticsCollector[F] {
    override def incrementInBytes(bytes: Long): F[Unit]  = incrementInBytes.inc(bytes)
    override def incrementOutBytes(bytes: Long): F[Unit] = incrementOutBytes.inc(bytes)
    override def registerRead(bytes: Long): F[Unit]      = registerRead.observe(bytes.toDouble)
    override def registerWrite(bytes: Long): F[Unit]     = registerWrite.observe(bytes.toDouble)
  }
}
