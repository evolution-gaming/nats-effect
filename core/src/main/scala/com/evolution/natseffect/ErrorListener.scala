package com.evolution.natseffect

import cats.effect.Async
import cats.effect.std.Dispatcher as CEDispatcher
import com.evolution.natseffect.impl.UnwrappedErrorListener

/** This library groups problems into four categories: <dl> <dt>Errors</dt> <dd>The server sent an error message using the {@code -err}
  * protocol operation.</dd> <dt>Exceptions</dt> <dd>A Java exception occurred, and was handled by the library.</dd> <dt>Slow Consumers</dt>
  * <dd>One of the connections consumers, Subscription or Dispatcher, is slow, and starting to drop messages.</dd> <dt>Fast Producers</dt>
  * <dd>One of the connections producers is too fast, and is discarding messages</dd> </dl> <p>All of these problems are reported to the
  * application code using the ErrorListener. The listener is configured in the [[Options]] at creation time.
  */
trait ErrorListener[F[_]] {

  /** NATS related errors that occur asynchronously in the client library are sent to an ErrorListener via errorOccurred. The ErrorListener
    * can use the error text to decide what to do about the problem. <p>The text for an error is described in the protocol doc at
    * `https://nats.io/documentation/internals/nats-protocol`. <p>In some cases the server will close the clients connection after sending
    * one of these errors. In that case, the connections [[ConnectionListener]] will be notified.
    *
    * @param conn
    *   The connection associated with the error
    * @param error
    *   The text of error that has occurred, directly from the server
    */
  def errorOccurred(conn: Connection[F], error: String): F[Unit]

  /** Exceptions that occur in the "normal" course of operations are sent to the ErrorListener using exceptionOccurred. Examples include,
    * application exceptions during Dispatcher callbacks, IOExceptions from the underlying socket, etc.. The library will try to handle
    * these, via reconnect or catching them, but they are forwarded here in case the application code needs them for debugging purposes.
    *
    * @param conn
    *   The connection associated with the error
    * @param exp
    *   The exception that has occurred, and was handled by the library
    */
  def exceptionOccurred(conn: Connection[F], exp: Exception): F[Unit]

  /** Called by the connection when a &quot;slow&quot; consumer is detected. This call is only made once until the consumer stops being
    * slow. At which point it will be called again if the consumer starts being slow again.
    *
    * <p>See [[Consumer.setPendingLimits]] for information on how to configure when this method is fired.
    *
    * <p> Slow consumers will result in dropped messages each consumer provides a method for retrieving the count of dropped messages, see
    * [[Consumer.droppedCount]].
    *
    * @param conn
    *   The connection associated with the error
    * @param consumer
    *   The consumer that is being marked slow
    */
  def slowConsumerDetected(conn: Connection[F], consumer: Consumer[F]): F[Unit]

  /** Called by the connection when a message is discarded.
    *
    * @param conn
    *   The connection that discarded the message
    * @param msg
    *   The message that is discarded
    */
  def messageDiscarded(conn: Connection[F], msg: Message): F[Unit]

  /** Called by the connection when a low level socket write timeout occurs.
    *
    * @param conn
    *   The connection that had the issue
    */
  def socketWriteTimeout(conn: Connection[F]): F[Unit]

  private[natseffect] def asJava(dispatcher: CEDispatcher[F], options: Options[F])(implicit F: Async[F]): UnwrappedErrorListener[F] =
    new UnwrappedErrorListener[F](this, dispatcher, options)

}
