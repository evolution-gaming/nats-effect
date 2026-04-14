package com.evolution.natseffect.jetstream

import com.evolution.natseffect.Message
import io.nats.client.impl.{AckType, NatsJetStreamMetaData}

import scala.concurrent.duration.FiniteDuration

/** A message received from a JetStream consumer with support for acknowledgment and metadata access.
  *
  * <p>JetStreamMessage extends the core NATS Message with JetStream-specific functionality: <ul> <li>Acknowledgment: ACK, NAK, Term, and
  * InProgress <li>Metadata: Stream sequence, consumer sequence, delivery count, timestamps <li>Redelivery tracking: Number of times the
  * message has been delivered </ul>
  *
  * <p>Acknowledgment semantics: <ul> <li>`ack`: Acknowledge successful processing (message won't be redelivered) <li>`nak`: Negative
  * acknowledgment (message will be redelivered immediately) <li>`term`: Terminate processing (message won't be redelivered to any consumer)
  * <li>`inProgress`: Extend the ack wait timer (useful for long-running processing) </ul>
  *
  * <p>This trait wraps the Java NATS JetStream Message API.
  *
  * @see
  *   [[https://docs.nats.io/nats-concepts/jetstream/consumers JetStream Consumers Documentation]]
  */
trait JetStreamMessage[F[_]] extends Message {

  /** Get the JetStream metadata for this message.
    *
    * @return
    *   effect yielding metadata including stream/consumer sequence, delivery count, timestamps
    */
  def metaData: F[NatsJetStreamMetaData]

  /** Get the last acknowledgment type sent for this message.
    *
    * @return
    *   effect yielding the last AckType (AckAck, AckNak, AckTerm, AckProgress)
    */
  def lastAck: F[AckType]

  /** Acknowledge this message, indicating successful processing. The message will not be redelivered.
    *
    * @return
    *   effect that completes when acknowledgment is sent
    */
  def ack: F[Unit]

  /** Acknowledge this message with a timeout, waiting for server confirmation.
    *
    * @param timeout
    *   the maximum time to wait for server acknowledgment
    * @return
    *   effect that completes when acknowledgment is confirmed or timeout occurs
    */
  def ackSync(timeout: FiniteDuration): F[Unit]

  /** Negative acknowledge this message, indicating processing failure. The message will be redelivered immediately.
    *
    * @return
    *   effect that completes when NAK is sent
    */
  def nak: F[Unit]

  /** Negative acknowledge this message with a delay before redelivery.
    *
    * @param nakDelay
    *   the time to wait before redelivering this message
    * @return
    *   effect that completes when NAK is sent
    */
  def nakWithDelay(nakDelay: FiniteDuration): F[Unit]

  /** Terminate processing of this message. The message will not be redelivered to any consumer.
    *
    * @return
    *   effect that completes when termination is sent
    */
  def term: F[Unit]

  /** Send an in-progress acknowledgment to extend the ack wait timer. Useful for long-running message processing.
    *
    * @return
    *   effect that completes when in-progress signal is sent
    */
  def inProgress: F[Unit]

}
