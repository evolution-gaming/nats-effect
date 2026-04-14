package com.evolution.natseffect

import io.nats.client.ConnectionListener.Events

/** Applications can use a ConnectionListener to track the status of a [[Connection]]. The listener is configured in the [[Options]] at
  * creation time.
  */
trait ConnectionListener[F[_]] {

  /** Connection related events that occur asynchronously in the client code are sent to a ConnectionListener via a single method. The
    * ConnectionListener can use the event type to decide what to do about the problem.
    *
    * @param conn
    *   the connection associated with the error
    * @param type
    *   the type of event that has occurred
    * @param time
    *   the time of the event, milliseconds since 1/1/1970
    * @param uriDetails
    *   extra details about the uri related to this connection event
    */
  def connectionEvent(conn: Connection[F], `type`: Events, time: Long, uriDetails: String): F[Unit]

}
