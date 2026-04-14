package com.evolution.natseffect.jetstream

import cats.effect.Async
import cats.effect.std.Dispatcher as CEDispatcher
import com.evolution.natseffect.impl.UnwrappedErrorListener
import com.evolution.natseffect.jetstream.impl.UnwrappedJetStreamErrorListener
import com.evolution.natseffect.{Connection, ErrorListener, Options}
import io.nats.client.support.Status

/** Error listener for JetStream-specific error conditions and warnings.
  *
  * <p>JetStreamErrorListener extends the base ErrorListener with additional callbacks for JetStream-specific events: <ul> <li>Heartbeat
  * alarms: Triggered when a consumer doesn't receive expected heartbeats <li>Pull status warnings: Non-critical status messages from pull
  * consumers <li>Pull status errors: Critical errors from pull consumers </ul>
  *
  * <p>These callbacks allow applications to monitor and react to JetStream-specific issues such as: <ul> <li>Consumer lag or disconnection
  * <li>Stream/consumer configuration issues <li>Pull subscription timeout or threshold issues </ul>
  *
  * @see
  *   [[https://docs.nats.io/nats-concepts/jetstream/consumers JetStream Consumers Documentation]]
  */
trait JetStreamErrorListener[F[_]] extends ErrorListener[F] {

  /** Called when a consumer heartbeat is missed, indicating potential connection or lag issues.
    *
    * @param conn
    *   the NATS connection
    * @param sub
    *   the subscription missing heartbeats
    * @param lastStreamSequence
    *   the last stream sequence number received
    * @param lastConsumerSequence
    *   the last consumer sequence number received
    * @return
    *   effect to handle the heartbeat alarm
    */
  def heartbeatAlarm(conn: Connection[F], sub: JetStreamSubscription[F], lastStreamSequence: Long, lastConsumerSequence: Long): F[Unit]

  /** Called when a pull consumer receives a non-critical warning status.
    *
    * @param conn
    *   the NATS connection
    * @param sub
    *   the subscription that received the warning
    * @param status
    *   the status message from the server
    * @return
    *   effect to handle the warning
    */
  def pullStatusWarning(conn: Connection[F], sub: JetStreamSubscription[F], status: Status): F[Unit]

  /** Called when a pull consumer receives a critical error status.
    *
    * @param conn
    *   the NATS connection
    * @param sub
    *   the subscription that received the error
    * @param status
    *   the status message from the server
    * @return
    *   effect to handle the error
    */
  def pullStatusError(conn: Connection[F], sub: JetStreamSubscription[F], status: Status): F[Unit]

  override private[natseffect] def asJava(
    dispatcher: CEDispatcher[F],
    options: Options[F]
  )(implicit F: Async[F]): UnwrappedErrorListener[F] =
    new UnwrappedJetStreamErrorListener[F](this, dispatcher, options)

}
