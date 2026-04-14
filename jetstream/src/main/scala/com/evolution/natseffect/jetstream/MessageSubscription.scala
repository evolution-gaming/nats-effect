package com.evolution.natseffect.jetstream

/** Subscription handle for active message consumption from a JetStream consumer.
  *
  * <p>MessageSubscription represents an active subscription consuming messages from a JetStream consumer. It provides methods to query
  * consumer state and control the subscription lifecycle.
  *
  * <p>Lifecycle: <ul> <li>Active: Subscription is receiving and processing messages <li>Stopped: Subscription has been stopped but not
  * closed <li>Finished: All messages have been delivered and subscription completed <li>Closed: Subscription resources have been released
  * </ul>
  *
  * <p>Best practice is to manage subscriptions as Resources, which automatically handle stopping and closing.
  *
  * @see
  *   [[ConsumerContext.consume]]
  */
trait MessageSubscription[F[_]] {

  /** Get the name of the consumer this subscription is attached to.
    *
    * @return
    *   effect yielding the consumer name
    */
  def getConsumerName: F[String]

  /** Get detailed information about the consumer this subscription is attached to.
    *
    * @return
    *   effect yielding ConsumerInfo with state and statistics
    */
  def getConsumerInfo: F[ConsumerInfo]

  /** Get cached consumer information if available, without making a server request.
    *
    * @return
    *   effect yielding Some(ConsumerInfo) if cached, None otherwise
    */
  def getCachedConsumerInfo: F[Option[ConsumerInfo]]

  /** Stop receiving new messages on this subscription. Already delivered messages will complete processing.
    *
    * @return
    *   effect that completes when subscription is stopped
    */
  def stop: F[Unit]

  /** Close the subscription and release resources. Should be called after stop.
    *
    * @return
    *   effect that completes when subscription is closed
    */
  def close: F[Unit]

  /** Check if this subscription has been stopped.
    *
    * @return
    *   effect yielding true if stopped, false otherwise
    */
  def isStopped: F[Boolean]

  /** Check if this subscription has finished (all messages delivered).
    *
    * @return
    *   effect yielding true if finished, false otherwise
    */
  def isFinished: F[Boolean]

}
