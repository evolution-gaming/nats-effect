package com.evolution.natseffect

/** A Consumer in the NATS library is an object that represents an incoming queue of messages.
  *
  * <p>A slow consumer is defined as a consumer that is not handling messages as quickly as they are arriving.
  *
  * <p>By default the library will allow a consumer to be a bit slow, at times, by caching messages for it in a queue. The size of this
  * queue is determined by [[Consumer.setPendingLimits]]. When a consumer maxes out its queue size, either by message count or bytes, the
  * library will start to drop messages.
  */
trait Consumer[F[_]] {

  /** Set limits on the maximum number of messages, or maximum size of messages this consumer will hold before it starts to drop new
    * messages waiting for the application to drain the queue.
    *
    * <p>Messages are dropped as they encounter a full queue, which is to say, new messages are dropped rather than old messages. If a queue
    * is 10 deep and fills up, the 11th message is dropped.
    *
    * <p>Sizes are checked after the fact, so if the max bytes is too small for the first pending message to arrive, it will still be
    * stored, only the next message will fail.
    *
    * <p>Setting a value to anything less than or equal to 0 will disable this check.
    *
    * @param maxMessages
    *   the maximum message count to hold, defaults to {@value #DEFAULT_MAX_MESSAGES}
    * @param maxBytes
    *   the maximum bytes to hold, defaults to {@value #DEFAULT_MAX_BYTES}
    */
  def setPendingLimits(maxMessages: Long, maxBytes: Long): F[Unit]

  /** @return
    *   the pending message limit set by [[Consumer.setPendingLimits]]
    */
  def pendingMessageLimit: F[Long]

  /** @return
    *   the pending byte limit set by [[Consumer.setPendingLimits]]
    */
  def pendingByteLimit: F[Long]

  /** @return
    *   the number of messages waiting to be delivered/popped
    */
  def pendingMessageCount: F[Long]

  /** @return
    *   the cumulative size of the messages waiting to be delivered/popped
    */
  def pendingByteCount: F[Long]

  /** @return
    *   the total number of messages delivered to this consumer, for all time
    */
  def deliveredCount: F[Long]

  /** @return
    *   the number of messages dropped from this consumer, since the last call to [[Consumer.clearDroppedCount]]
    */
  def droppedCount: F[Long]

  /** Reset the drop count to 0.
    */
  def clearDroppedCount(): F[Unit]

}
