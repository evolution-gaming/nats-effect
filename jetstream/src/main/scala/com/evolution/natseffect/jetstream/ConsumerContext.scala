package com.evolution.natseffect.jetstream

/** Consumer context for durable JetStream consumers.
  *
  * <p>A ConsumerContext represents a durable consumer on a JetStream stream. Durable consumers maintain their state (current position,
  * acknowledgment status, etc.) across restarts and can be shared by multiple connections.
  *
  * <p>Key features of durable consumers: <ul> <li>Persistent state: Progress is maintained even if all subscribers disconnect <li>Multiple
  * subscriptions: Multiple subscribers can share the same consumer (work queue pattern) <li>Configurable acknowledgment: Support for
  * explicit, automatic, or no acknowledgment <li>Redelivery: Unacknowledged messages are redelivered after ack wait timeout <li>Flow
  * control: Support for rate limiting and batching </ul>
  *
  * <p>This trait extends BaseConsumerContext with methods to query consumer information and state.
  *
  * @see
  *   [[https://docs.nats.io/nats-concepts/jetstream/consumers JetStream Consumers Documentation]]
  */
trait ConsumerContext[F[_]] extends BaseConsumerContext[F] {

  /** Get the name of this consumer.
    *
    * @return
    *   effect yielding the consumer name
    */
  def getConsumerName: F[String]

  /** Get detailed information about this consumer including configuration and current state.
    *
    * @return
    *   effect yielding ConsumerInfo with configuration, delivered count, ack pending, etc.
    */
  def getConsumerInfo: F[ConsumerInfo]

  /** Get cached consumer information if available, without making a server request.
    *
    * @return
    *   effect yielding Some(ConsumerInfo) if cached, None otherwise
    */
  def getCachedConsumerInfo: F[Option[ConsumerInfo]]

}
