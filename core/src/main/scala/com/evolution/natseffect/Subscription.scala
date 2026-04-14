package com.evolution.natseffect

/** A Subscription encapsulates an incoming queue of messages associated with a single subject and optional queue name. Subscriptions can be
  * in one of two modes. The subscription is owned by a [[Dispatcher]]. For every subscription `io.nats.client.Subscription` is created
  * under the hood.
  *
  * <p>Subscriptions support the concept of auto-unsubscribe. This concept is a bit tricky, as it involves the client library, the server
  * and the Subscriptions history. If told to unsubscribe after 5 messages, a subscription will stop receiving messages when one of the
  * following occurs: <ul> <li>The subscription delivers 5 messages with next messages, <em>including any previous messages</em>. <li>The
  * server sends 5 messages to the subscription. <li>The subscription already received 5 or more messages. </ul> <p>In the case of a
  * reconnect, the remaining message count, as maintained by the subscription, will be sent to the server. So if you unsubscribe with a max
  * of 5, then disconnect after 2, the new server will be told to unsubscribe after 3. <p>The other, possibly confusing case, is that
  * unsubscribe is based on total messages. So if you make a subscription and receive 5 messages on it, then say unsubscribe with a maximum
  * of 5, the subscription will immediately stop handling messages.
  */
trait Subscription[F[_]] {

  /** @return
    *   the subject associated with this subscription
    */
  def subject: String

  /** @return
    *   the queue associated with this subscription
    */
  def queueName: Option[String]

  /** @return
    *   the Dispatcher that owns this subscription
    */
  def dispatcher: Dispatcher[F]

  /** Unsubscribe this subscription and stop listening for messages.
    *
    * <p>Stops messages to the subscription locally and notifies the server.
    *
    * @return
    *   and effect which can throw `IllegalStateException`` if the subscription is not active
    */
  def unsubscribe: F[Unit]

  /** Unsubscribe this subscription and stop listening for messages, after the specified number of messages.
    *
    * <p>If the subscription has already received <code>after</code> messages, it will not receive more. The provided limit is a lifetime
    * total for the subscription, with the caveat that if the subscription already received more than <code>after</code> when unsubscribe is
    * called the client will not travel back in time to stop them.
    *
    * @param after
    *   the number of messages to accept before unsubscribing
    * @return
    *   * and effect which can throw `IllegalStateException` if the subscription is not active
    */
  def unsubscribe(after: Int): F[Unit]

}
