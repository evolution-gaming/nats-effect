package com.evolution.natseffect

trait Statistics[F[_]] {

  def pings: F[Long]

  def reconnects: F[Long]

  def droppedCount: F[Long]

  def OKs: F[Long]

  def errs: F[Long]

  def exceptions: F[Long]

  def requestsSent: F[Long]

  def repliesReceived: F[Long]

  def duplicateRepliesReceived: F[Long]

  def orphanRepliesReceived: F[Long]

  def inMsgs: F[Long]

  def outMsgs: F[Long]

  def inBytes: F[Long]

  def outBytes: F[Long]

  def flushCounter: F[Long]

  def outstandingRequests: F[Long]

}
