package com.evolution.natseffect.impl

import com.evolution.natseffect.Message
import io.nats.client.support.Status
import io.nats.client.impl.Headers

private[natseffect] class WrappedMessage(wrapped: JMessage) extends Message with JavaWrapper[JMessage] {

  override def subject: String = wrapped.getSubject

  override def replyTo: Option[String] = Option(wrapped.getReplyTo)

  override def headers: Option[Headers] = Option(wrapped.getHeaders)

  override def status: Option[Status] = Option(wrapped.getStatus)

  override def data: Option[Array[Byte]] = Option(wrapped.getData).filter(_.nonEmpty)

  override def SID: String = wrapped.getSID

  override def asJava: JMessage = wrapped
}
