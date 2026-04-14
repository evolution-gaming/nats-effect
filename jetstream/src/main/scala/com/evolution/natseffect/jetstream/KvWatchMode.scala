package com.evolution.natseffect.jetstream

/** Watch mode for key-value store subscriptions.
  *
  * <p>KvWatchMode determines which updates a key-value watch subscription will receive: <ul> <li>`LatestValues`: Receive only new updates
  * (starting from current state) <li>`AllHistory`: Receive all historical updates for keys, then continue with new updates <li>
  * `FromRevision`: Start from a specific revision number and receive all updates from that point </ul>
  *
  * <p>Use cases: <ul> <li>`LatestValues`: Real-time monitoring of changes <li>`AllHistory`: Full replay for rebuilding state <li>
  * `FromRevision`: Resume from known checkpoint </ul>
  *
  * @see
  *   [[KeyValue.watchAll]]
  * @see
  *   [[KeyValue.watch]]
  */
sealed trait KvWatchMode

object KvWatchMode {

  /** Watch for only new updates, starting from the current state.
    *
    * <p>This is the most common mode for real-time subscriptions. You'll receive only updates that occur after the watch is established.
    */
  case object LatestValues extends KvWatchMode

  /** Watch all historical updates for keys, then continue with new updates.
    *
    * <p>This mode delivers the complete history of all keys in the bucket, followed by any new updates. Useful for rebuilding state or
    * replaying events.
    */
  case object AllHistory extends KvWatchMode

  /** Watch starting from a specific revision number.
    *
    * <p>This mode allows you to resume from a known checkpoint. All updates from the specified revision onward will be delivered.
    *
    * @param revision
    *   the revision number to start from
    */
  case class FromRevision(revision: Long) extends KvWatchMode

}
