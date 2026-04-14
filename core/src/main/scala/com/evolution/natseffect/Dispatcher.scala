package com.evolution.natseffect

/** This library uses the concept of a Dispatcher to organize message callbacks in a way that the application can control. The dispatcher
  * has 0 or more subscriptions associated with it. This means that a group of subscriptions, or subjects, can be combined into a single
  * sequential callback. But, multiple dispatchers can be created to handle different groups of subscriptions/subjects.
  *
  * <p>Unlike `io.nats.client.Dispatcher` the Scala implementation does not create a thread for every dispatcher instance, but relies on the
  * cats-effect concurrency. There is only one Java dispatcher per [[Connection]].
  *
  * <p>Dispatchers are created from the connection using [[Connection.createDispatcher]] method, which returns a `Resource`. When the
  * Resource is released all created subscriptions are terminated.
  *
  * <p><em>See the documentation on [[Consumer]] for configuring behavior in a slow consumer situation.</em>
  */
trait Dispatcher[F[_]] extends Consumer[F] {

  /** Create a subscription to the specified subject under the control of this dispatcher. The Dispatcher will not prevent duplicate
    * subscriptions from being made.
    *
    * @param subject
    *   The subject to subscribe to.
    * @param queue
    *   The queue group to join.
    * @param messageHandler
    *   The target for the messages
    * @return
    *   An effect for the Subscription, so subscriptions may be later unsubscribed manually. The effect can be failed with
    *   `IllegalStateException` if the dispatcher was previously closed
    */
  def subscribe(subject: String, queue: Option[String] = None)(messageHandler: Message => F[Unit]): F[Subscription[F]]

  /** Unsubscribe from the specified subject. The method is applied to all subscriptions with the specified subject
    *
    * <p>Stops messages to the subscription locally and notifies the server.
    *
    * @param subject
    *   The subject to unsubscribe from.
    * @return
    *   an effect which can be failed `IllegalStateException` if the dispatcher was previously closed
    */
  def unsubscribe(subject: String): F[Unit]

}
