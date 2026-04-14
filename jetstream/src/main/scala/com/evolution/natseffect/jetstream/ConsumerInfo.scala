package com.evolution.natseffect.jetstream

import io.nats.client.api.{ClusterInfo, ConsumerConfiguration, PriorityGroupState, SequenceInfo}

import java.time.ZonedDateTime
import scala.concurrent.duration.FiniteDuration

/** The ConsumerInfo trait returns information about a JetStream consumer.
  */
trait ConsumerInfo {

  /** The consumer configuration representing this consumer.
    *
    * @return
    *   the config
    */
  def consumerConfiguration: ConsumerConfiguration

  /** A unique name for the consumer, either machine generated or the durable name
    *
    * @return
    *   the name
    */
  def name: String

  /** The Stream the consumer belongs to
    *
    * @return
    *   the stream name
    */
  def streamName: String

  /** Gets the creation time of the consumer.
    *
    * @return
    *   the creation date and time.
    */
  def creationTime: ZonedDateTime

  /** The last message delivered from this Consumer
    *
    * @return
    *   the last delivered sequence info
    */
  def delivered: SequenceInfo

  /** The highest contiguous acknowledged message
    *
    * @return
    *   the sequence info
    */
  def ackFloor: SequenceInfo

  /** The number of messages left unconsumed in this Consumer
    *
    * @return
    *   the number of pending messages
    */
  def numPending: Long

  /** The number of pull consumers waiting for messages
    *
    * @return
    *   the number of waiting messages
    */
  def numWaiting: Long

  /** The number of messages pending acknowledgement
    *
    * @return
    *   the number of messages
    */
  def numAckPending: Long

  /** The number of redeliveries that have been performed
    *
    * @return
    *   the number of redeliveries
    */
  def redelivered: Long

  /** Indicates if the consumer is currently in a paused state
    *
    * @return
    *   true if paused
    */
  def paused: Boolean

  /** When paused the time remaining until unpause
    *
    * @return
    *   the time remaining
    */
  def pauseRemaining: Option[FiniteDuration]

  /** Information about the cluster for clustered environments
    *
    * @return
    *   the cluster info object
    */
  def clusterInfo: Option[ClusterInfo]

  /** Indicates if any client is connected and receiving messages from a push consumer
    *
    * @return
    *   the flag
    */
  def pushBound: Boolean

  /** Gets the server time the info was gathered
    *
    * @return
    *   the server gathered timed
    */
  def timestamp: Option[ZonedDateTime]

  /** The state of Priority Groups
    *
    * @return
    *   the list of Priority Groups, may be null
    */
  def priorityGroupStates: Option[List[PriorityGroupState]]

  /** A way to more accurately calculate pending during the initial state of the consumer when messages may be unaccounted for in flight
    *
    * @return
    *   the calculated amount
    */
  def calculatedPending: Long
}
