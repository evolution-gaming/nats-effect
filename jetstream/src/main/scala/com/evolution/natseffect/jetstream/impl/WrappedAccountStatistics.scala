package com.evolution.natseffect.jetstream.impl

import com.evolution.natseffect.impl.JavaWrapper
import com.evolution.natseffect.jetstream.AccountStatistics
import io.nats.client.api.{AccountLimits, AccountTier, ApiStats}

import scala.jdk.CollectionConverters.MapHasAsScala

private[natseffect] class WrappedAccountStatistics(wrapped: JAccountStatistics)
    extends AccountStatistics
    with JavaWrapper[JAccountStatistics] {
  override def memory: Long = wrapped.getMemory

  override def storage: Long = wrapped.getStorage

  override def reservedMemory: Long = wrapped.getReservedMemory

  override def reservedStorage: Long = wrapped.getReservedStorage

  override def streams: Long = wrapped.getStreams

  override def consumers: Long = wrapped.getConsumers

  override def limits: AccountLimits = wrapped.getLimits

  override def domain: Option[String] = Option(wrapped.getDomain)

  override def api: ApiStats = wrapped.getApi

  override def tiers: Map[String, AccountTier] = wrapped.getTiers.asScala.toMap

  override def asJava: JAccountStatistics = wrapped
}
