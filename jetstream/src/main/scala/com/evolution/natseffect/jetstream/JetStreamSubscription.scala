package com.evolution.natseffect.jetstream

/** JetStream subscription handle exposed in error listener callbacks.
  *
  * <p>This trait is used to wrap lower-level subscription objects that are exposed in the [[JetStreamErrorListener]] API. It provides
  * access to consumer and stream information for error handling and monitoring purposes.
  *
  * @see
  *   [[JetStreamErrorListener]]
  */
trait JetStreamSubscription[F[_]] {

  /** Get the name of the consumer this subscription is attached to.
    *
    * @return
    *   effect yielding the consumer name
    */
  def getConsumerName: F[String]

  /** Get the name of the stream this subscription is consuming from.
    *
    * @return
    *   effect yielding the stream name
    */
  def getStreamName: F[String]

  /** Get detailed information about the consumer.
    *
    * @return
    *   effect yielding ConsumerInfo with state and statistics
    */
  def getConsumerInfo: F[Option[ConsumerInfo]]

}
