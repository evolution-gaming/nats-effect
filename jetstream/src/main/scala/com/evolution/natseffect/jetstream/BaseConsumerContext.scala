package com.evolution.natseffect.jetstream

import cats.effect.Resource
import io.nats.client.ConsumeOptions

/** Base trait for consumer contexts providing message consumption functionality.
  *
  * <p>BaseConsumerContext is the foundation for both durable and ordered consumer contexts. It provides the core `consume` method for
  * processing messages from a JetStream stream.
  *
  * <p>The consume method returns a Resource that manages the subscription lifecycle. When the resource is released, the subscription is
  * automatically stopped and cleaned up.
  *
  * @see
  *   [[ConsumerContext]]
  * @see
  *   [[OrderedConsumerContext]]
  */
trait BaseConsumerContext[F[_]] {

  /** Start consuming messages from this consumer, invoking the handler for each message.
    *
    * <p>Messages are delivered to the handler sequentially.
    *
    * @param handler
    *   the function to handle each message
    * @param consumeOptions
    *   optional consume options (batch size, expiry, threshold, etc.)
    * @return
    *   a Resource managing the message subscription lifecycle
    */
  def consume(
    handler: JetStreamMessage[F] => F[Unit],
    consumeOptions: ConsumeOptions = ConsumeOptions.DEFAULT_CONSUME_OPTIONS
  ): Resource[F, MessageSubscription[F]]

}
