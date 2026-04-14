package com.evolution.natseffect

import com.evolution.natseffect.impl.WrappedMessage
import io.nats.client.impl.{Headers, NatsMessage}
import io.nats.client.support.Status
import io.nats.client.Message as JMessage

/** The NATS library uses a Message object to encapsulate incoming messages. Applications publish and send requests with raw strings and
  * Array[Byte] but incoming messages can have a few values, so they need a wrapper.
  *
  * <p>The Array[Byte] returned by [[Message.data]] is not shared with any library code and is safe to manipulate.
  *
  * NOTICE: This interface is intended only to be implemented internally, although since it is public it is technically available to anyone
  * and breaking changes should be avoided. For instance:
  *   - Signatures should not be changed.
  *   - If methods are added, a default implementation should be provided.
  */
trait Message {

  /** @return
    *   the subject that this message was sent to
    */
  def subject: String

  /** @return
    *   the subject the application is expected to send a reply message on
    */
  def replyTo: Option[String]

  /** @return
    *   the headers object for the message
    */
  def headers: Option[Headers]

  /** @return
    *   the status object message
    */
  def status: Option[Status]

  /** @return
    *   the data from the message
    */
  def data: Option[Array[Byte]]

  /** @return
    *   the id associated with the subscription, used by the connection when processing an incoming message from the server
    */
  def SID: String

  /** @return
    *   Java-based message
    */
  def asJava: JMessage

}
object Message {
  def apply(subject: String, data: Option[Array[Byte]] = None, replyTo: Option[String] = None, headers: Option[Headers] = None): Message = {
    val javaMessage = new NatsMessage(subject, replyTo.orNull, headers.orNull, data.orNull)
    new WrappedMessage(javaMessage)
  }
}
