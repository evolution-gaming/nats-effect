package com.evolution.natseffect.jetstream

import io.nats.client.api.{ClusterInfo, MirrorInfo, SourceInfo, StreamAlternate, StreamConfiguration, StreamState}

import java.time.ZonedDateTime

/** The StreamInfo trait contains information about a JetStream stream.
  */
trait StreamInfo {

  /** Gets the stream configuration.
    *
    * @return
    *   the stream configuration.
    */
  def config: StreamConfiguration

  /** Gets the stream state.
    *
    * @return
    *   the stream state
    */
  def streamState: StreamState

  /** Gets the creation time of the stream.
    *
    * @return
    *   the creation date and time.
    */
  def createTime: ZonedDateTime

  /** Gets the mirror info
    *
    * @return
    *   the mirror info
    */
  def mirrorInfo: Option[MirrorInfo]

  /** Gets the source info
    *
    * @return
    *   the source info
    */
  def sourceInfos: Option[List[SourceInfo]]

  /** Gets the cluster info
    *
    * @return
    *   the cluster info
    */
  def clusterInfo: Option[ClusterInfo]

  /** Gets the stream alternates
    *
    * @return
    *   the stream alternates
    */
  def alternates: Option[List[StreamAlternate]]

  /** Gets the server time the info was gathered
    *
    * @return
    *   the server gathered timed
    */
  def timestamp: Option[ZonedDateTime]
}
