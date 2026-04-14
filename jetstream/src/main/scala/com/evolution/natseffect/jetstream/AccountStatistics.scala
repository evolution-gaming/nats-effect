package com.evolution.natseffect.jetstream

import io.nats.client.api.{AccountLimits, AccountTier, ApiStats}

/** The JetStream Account Statistics
  */
trait AccountStatistics {

  /** Gets the amount of memory storage used by the JetStream deployment. If the account has tiers, this will represent a rollup.
    *
    * @return
    *   memory in bytes
    */
  def memory: Long

  /** Gets the amount of file storage used by the JetStream deployment. If the account has tiers, this will represent a rollup.
    *
    * @return
    *   storage in bytes
    */
  def storage: Long

  /** Bytes that is reserved for memory usage by this account on the server
    *
    * @return
    *   the memory usage in bytes
    */
  def reservedMemory: Long

  /** Bytes that is reserved for disk usage by this account on the server
    *
    * @return
    *   the disk usage in bytes
    */
  def reservedStorage: Long

  /** Gets the number of streams used by the JetStream deployment. If the account has tiers, this will represent a rollup.
    *
    * @return
    *   stream maximum count
    */
  def streams: Long

  /** Gets the number of consumers used by the JetStream deployment. If the account has tiers, this will represent a rollup.
    *
    * @return
    *   consumer maximum count
    */
  def consumers: Long

  /** Gets the Account Limits object. If the account has tiers, the object will be present but all values will be zero. See the Account
    * Limits for the specific tier.
    *
    * @return
    *   the AccountLimits object
    */
  def limits: AccountLimits

  /** Gets the account domain. May be null
    *
    * @return
    *   the domain
    */
  def domain: Option[String]

  /** Gets the account api stats
    *
    * @return
    *   the ApiStats object
    */
  def api: ApiStats

  /** Gets the map of the Account Tiers by tier name. May be empty, but never null.
    *
    * @return
    *   the map
    */
  def tiers: Map[String, AccountTier]
}
