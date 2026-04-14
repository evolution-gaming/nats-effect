package com.evolution.natseffect.jetstream

import io.nats.client.api.OrderedConsumerConfiguration

/** Consumer context for ordered JetStream consumers.
  *
  * <p>An OrderedConsumerContext represents an ephemeral ordered consumer that guarantees strict message ordering. Ordered consumers are
  * designed for applications that need to process messages in exact sequence order.
  *
  * <p>Key features of ordered consumers: <ul> <li>Strict ordering: Messages are always delivered in order <li>Automatic recovery: Consumer
  * is automatically recreated on connection loss <li>Ephemeral: State is not persisted (no redelivery to other connections) <li>No
  * acknowledgments: Messages are automatically acknowledged <li>Single subscriber: Only one subscriber allowed per ordered consumer </ul>
  *
  * <p>This trait extends BaseConsumerContext with access to the consumer's configuration.
  *
  * @see
  *   [[https://docs.nats.io/nats-concepts/jetstream/consumers#ordered-consumers JetStream Ordered Consumers Documentation]]
  */
trait OrderedConsumerContext[F[_]] extends BaseConsumerContext[F] {

  /** Get the configuration for this ordered consumer.
    *
    * @return
    *   the OrderedConsumerConfiguration
    */
  def config: OrderedConsumerConfiguration

  /** Get the name of this ordered consumer, if assigned.
    *
    * @return
    *   effect yielding Some(name) if available, None for ephemeral ordered consumers
    */
  def getConsumerName: F[Option[String]]

}
