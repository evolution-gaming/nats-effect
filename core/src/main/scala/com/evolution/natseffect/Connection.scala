package com.evolution.natseffect

import cats.effect.Resource
import io.nats.client.Connection.Status
import io.nats.client.ForceReconnectOptions
import io.nats.client.api.ServerInfo
import io.nats.client.impl.Headers

import java.net.InetAddress
import scala.concurrent.duration.{Duration, FiniteDuration}

/** The Connection class is at the heart of the NATS Scala client. Fundamentally a connection represents a single network connection to the
  * NATS server. For every instance `io.nats.client.Connection` and `io.nats.client.Dispatcher` are created.
  *
  * <p>Each connection you create will result in the creation of a single socket and several threads: <ul> <li> A reader thread to receive
  * data from the peer NATS server <li> A writer thread for outbound data transmission <li> A timer thread for a few maintenance timers
  * </ul>
  *
  * <p>The connection has a `io.nats.client.Connection.Status` Status which can be checked using the [[Connection.status]] method or watched
  * using a [[ConnectionListener]].
  *
  * <p>Connections, by default, are configured to try to reconnect to the server if there is a network failure up to
  * `io.nats.client.Options.DEFAULT_MAX_RECONNECT`. You can configure this behavior in the [[Options]]. Moreover, the options allows you to
  * control whether reconnect happens in the same order every time, and the time to wait if trying to reconnect to the same server over and
  * over.
  *
  * <p>The Initial list of NATS servers is provided to Connection by the Options parameter. Each time a connection is established, this list
  * is internally extended with additional addresses, provided by the peer server. For example, if you connect to serverA, it can tell the
  * connection &quot;i know about serverB and serverC&quot;. If serverA goes down the client library will try to connect to serverA, serverB
  * and serverC. Now, if the library connects to serverB, it may tell the client &quot;i know about serverB and serverE&quot;. The client's
  * list of servers, available from [[Connection.servers]] will now be serverA from the initial connect, serverB and serverE, the reference
  * to serverC is lost.
  *
  * <p>A connection is created as a `Resource`. When the resource is released the threads and socket are cleaned up.
  *
  * <p>All outgoing messages are sent through the connection object using one of the `Connection.publish` methods or the
  * `Connection.request`` method. When publishing you can specify a reply to subject which can be retrieved by the receiver to respond. The
  * request method will handle this behavior itself, but it relies on getting the value out of a Future so may be less flexible than publish
  * with replyTo set.
  *
  * <p>Messages can be received by creating a [[Dispatcher]]. The Dispatcher will listen for messages on one or more subscriptions. The
  * Dispatcher groups a set of subscriptions into a single listener that sequentially calls application code for each message.
  *
  * <p>Applications can use the [[Connection.flush flush]] method to check that published messages have made it to the server. However, this
  * method initiates a round trip to the server and waits for the response so it should be used sparingly. Eventually the messages will be
  * delivered to the server anyway, unless the connection is closed ungracefully. To make sure that all messages have been delivered before
  * the `Resource` is released use the [[Options.drainTimeout]] parameter.
  *
  * <p>The connection provides two listeners via the Options. The [[ConnectionListener]] can be used to listen for lifecycle events. The
  * [[ErrorListener]] provides three callback opportunities including slow consumers, error messages from the server and exceptions handled
  * by the client library. These listeners can only be set at creation time using the [[Options]].
  *
  * <p><em>Note</em>: The publish methods take an array of bytes. These arrays <strong>will not be copied</strong>. This design choice is
  * based on the common case of strings or objects being converted to bytes. Once a client can be sure a message was received by the NATS
  * server it is theoretically possible to reuse that byte array, but this pattern should be treated as advanced and only used after
  * thorough testing.
  */
trait Connection[F[_]] {

  /** Send a message to the specified subject. The message body <strong>will not</strong> be copied. The expected usage with string content
    * is something like:
    *
    * {{{
    *   for {
    *     nc <- Nats.connect[IO]()
    *     _  <- nc.publish("destination", "message".getBytes("UTF-8"))
    *   } yield ()
    * }}}
    *
    * where the sender creates a byte array immediately before calling publish. See `publish( String, String, Array[Byte])` for more details
    * on publish during reconnect.
    *
    * @param subject
    *   the subject to send the message to
    * @param body
    *   the message body
    * @return
    *   an effect which is completed when the message is sent. The created effect can fail with `IllegalStateException` if the reconnect
    *   buffer is exceeded
    */
  def publish(subject: String, body: Array[Byte]): F[Unit]

  /** Send a message to the specified subject. The message body <strong>will not</strong> be copied. The expected usage with string content
    * is something like:
    *
    * {{{
    *   for {
    *     nc <- Nats.connect[IO]()
    *     h = new Headers().put("key", "value")
    *     _  <- nc.publish("destination", h, "message".getBytes("UTF-8"))
    *   } yield ()
    * }}}
    *
    * where the sender creates a byte array immediately before calling publish. See `publish( String, String, Array[Byte])` for more details
    * on publish during reconnect.
    *
    * @param subject
    *   the subject to send the message to
    * @param headers
    *   Optional headers to publish with the message.
    * @param body
    *   the message body
    * @return
    *   * an effect which is completed when the message is sent. The created effect can fail with `IllegalStateException` if the reconnect *
    *   buffer is exceeded
    */
  def publish(subject: String, headers: Headers, body: Array[Byte]): F[Unit]

  /** Send a request to the specified subject, providing a replyTo subject. The message body <strong>will not</strong> be copied. The
    * expected usage with string content is something like:
    *
    * {{{
    *   for {
    *     nc <- Nats.connect[IO]()
    *     _  <- nc.publish("destination", "reply-to", "message".getBytes("UTF-8"))
    *   } yield ()
    * }}}
    *
    * where the sender creates a byte array immediately before calling publish. <p> During reconnect the client will try to buffer messages.
    * The buffer size is set in the connect options, see [[Options.reconnectBufferSize]] with a default value of
    * `io.nats.client.Options.DEFAULT_RECONNECT_BUF_SIZE` bytes. If the buffer is exceeded an IllegalStateException is thrown. Applications
    * should use this exception as a signal to wait for reconnect before continuing. </p>
    *
    * @param subject
    *   the subject to send the message to
    * @param replyTo
    *   the subject the receiver should send the response to
    * @param body
    *   the message body
    * @return
    *   * an effect which is completed when the message is sent. The created effect can fail with `IllegalStateException` if the reconnect *
    *   buffer is exceeded
    */
  def publish(subject: String, replyTo: String, body: Array[Byte]): F[Unit]

  /** Send a request to the specified subject, providing a replyTo subject. The message body <strong>will not</strong> be copied. The
    * expected usage with string content is something like:
    *
    * {{{
    *   for {
    *     nc <- Nats.connect[IO]()
    *     h = new Headers().put("key", "value")
    *     _  <- nc.publish("destination", "reply-to", h, "message".getBytes("UTF-8"))
    *   } yield ()
    * }}}
    *
    * where the sender creates a byte array immediately before calling publish. <p> During reconnect the client will try to buffer messages.
    * * The buffer size is set in the connect options, see [[Options.reconnectBufferSize]] with a default value of *
    * `io.nats.client.Options.DEFAULT_RECONNECT_BUF_SIZE` bytes. If the buffer is exceeded an IllegalStateException * is thrown.
    * Applications should use this exception as a signal to wait for reconnect before continuing. </p>
    *
    * @param subject
    *   the subject to send the message to
    * @param replyTo
    *   the subject the receiver should send the response to
    * @param headers
    *   Optional headers to publish with the message.
    * @param body
    *   the message body
    * @return
    *   * an effect which is completed when the message is sent. The created effect can fail with `IllegalStateException` if the reconnect *
    *   buffer is exceeded
    */
  def publish(subject: String, replyTo: String, headers: Headers, body: Array[Byte]): F[Unit]

  /** Send a message to the specified subject. The message body <strong>will not</strong> be copied. The expected usage with string content
    * is something like:
    *
    * {{{
    *   for {
    *     nc      <- Nats.connect[IO]()
    *     message = Message(...)
    *     _       <- nc.publish(message)
    *   } yield ()
    * }}}
    *
    * where the sender creates a byte array immediately before calling publish.
    *
    * @param message
    *   the message
    * @return
    *   * an effect which is completed when the message is sent. The created effect can fail with `IllegalStateException` if the reconnect *
    *   buffer is exceeded
    */
  def publish(message: Message): F[Unit]

  /** Send a request. The returned effect will be completed when the response comes back.
    *
    * @param subject
    *   the subject for the service that will handle the request
    * @param body
    *   the content of the message
    * @return
    *   an effect for the response, which may be failed with CancellationException or TimeoutException
    */
  def request(subject: String, body: Array[Byte]): F[Message]

  /** Send a request. The returned effect will be completed when the response comes back.
    *
    * @param subject
    *   the subject for the service that will handle the request
    * @param headers
    *   headers to publish with the message.
    * @param body
    *   the content of the message
    * @return
    *   an effect for the response, which may be failed with CancellationException or TimeoutException
    */
  def request(subject: String, headers: Headers, body: Array[Byte]): F[Message]

  /** Send a request. The returned effect will be completed when the response comes back.
    *
    * @param subject
    *   the subject for the service that will handle the request
    * @param body
    *   the content of the message
    * @param timeout
    *   the time to wait for a response
    * @return
    *   an effect for the response, which may be failed with CancellationException or TimeoutException
    */
  def requestWithTimeout(subject: String, body: Array[Byte], timeout: FiniteDuration): F[Message]

  /** Send a request. The returned effect will be completed when the response comes back.
    *
    * @param subject
    *   the subject for the service that will handle the request
    * @param body
    *   the content of the message
    * @param headers
    *   headers to publish with the message.
    * @param timeout
    *   the time to wait for a response
    * @return
    *   an effect for the response, which may be failed with CancellationException or TimeoutException
    */
  def requestWithTimeout(subject: String, headers: Headers, body: Array[Byte], timeout: FiniteDuration): F[Message]

  /** Send a request. The returned effect will be completed when the response comes back.
    *
    * <p>The Message object allows you to set a replyTo, but in requests, the replyTo is reserved for internal use as the address for the
    * server to respond to the client with the consumer's reply.</p>
    *
    * @param message
    *   the message
    * @return
    *   an effect for the response, which may be failed with CancellationException or TimeoutException
    */
  def request(message: Message): F[Message]

  /** Send a request. The returned effect will be completed when the response comes back.
    *
    * <p>The Message object allows you to set a replyTo, but in requests, the replyTo is reserved for internal use as the address for the
    * server to respond to the client with the consumer's reply.</p>
    *
    * @param message
    *   the message
    * @param timeout
    *   the time to wait for a response
    * @return
    *   an effect for the response, which may be failed with CancellationException or TimeoutException
    */
  def requestWithTimeout(message: Message, timeout: FiniteDuration): F[Message]

  /** Create a Resource with a [[Dispatcher]] for this connection. The dispatcher can group one or more subscriptions into a single
    * sequential callback. When the Resource is released all created subscriptions are being destroyed.
    *
    * {{{
    *   for {
    *     nc <- Nats.connect[IO]()
    *     d  <- nc.createDispatcher()
    *     _  <- d.subscribe("hello") { msg =>
    *          IO.println(msg)
    *        }.toResource
    *   } yield ()
    * }}}
    *
    * @return
    *   a Resource for the new Dispatcher
    */
  def createDispatcher(): Resource[F, Dispatcher[F]]

  /** Flush the connection's buffer of outgoing messages, including sending a protocol message to and from the server. If called while the
    * connection is closed, this method will immediately throw a TimeoutException, regardless of the timeout. If called while the connection
    * is disconnected due to network issues this method will wait for up to the timeout for a reconnect or close.
    *
    * @param timeout
    *   The time to wait for the flush to succeed
    * @return
    *   an effect which is completed when the connection is flushed. The created effect can fail with `InterruptedException` if the
    *   underlying thread is interrupted or `java.util.concurrent.TimeoutException` if the timeout is exceeded
    */
  def flush(timeout: Duration): F[Unit]

  /** Returns the connections current status.
    *
    * @return
    *   an effect with the connection's status
    */
  def status: F[Status]

  /** MaxPayload returns the size limit that a message payload can have. This is set by the server configuration and delivered to the client
    * upon connect.
    *
    * @return
    *   an effect with the maximum size of a message payload
    */
  def maxPayload: F[Long]

  /** Return the list of known server urls, including additional servers discovered after a connection has been established.
    *
    * @return
    *   an effect with this connection's list of known server URLs
    */
  def servers: F[Seq[String]]

  /** @return
    *   an effect with a wrapper for useful statistics about the connection
    */
  def statistics: F[Statistics[F]]

  /** @return
    *   the read-only options used to create this connection
    */
  def options: Options[F]

  /** @return
    *   an effect with the server information such as id, client info, etc.
    */
  def serverInfo: F[Option[ServerInfo]]

  /** @return
    *   an effect with the url used for the current connection
    */
  def connectionUrl: F[Option[String]]

  /** @return
    *   an effect with the InetAddress of client as known by the NATS server
    */
  def clientInetAddress: F[Option[InetAddress]]

  /** @return
    *   the error text from the last error sent by the server to this client
    */
  def lastError: F[Option[String]]

  /** @return
    *   an effect which clears the last error from the server
    */
  def clearLastError: F[Unit]

  /** @return
    *   an effect which generates a new inbox subject, can be used for directed replies from subscribers. These are guaranteed to be unique,
    *   but can be shared and subscribed to by others.
    */
  def createInbox: F[String]

  /** @return
    *   an effect which immediately flushes the underlying connection buffer if the connection is valid. The effect can be failed with
    *   `java.io.IOException` if the connection flush fails.
    */
  def flushBuffer: F[Unit]

  /** @return
    *   an effect which forces reconnect behavior. Stops the current connection including the reading and writing, copies already queued
    *   outgoing messages, and then begins the reconnect logic. Does not flush. Does not force close the connection. The effect can be
    *   failed with `java.io.IOException` if the forceReconnect fails or `InterruptedException` if the connection is not connected
    */
  def forceReconnect: F[Unit]

  /** Forces reconnect behavior. Stops the current connection including the reading and writing, copies already queued outgoing messages,
    * and then begins the reconnect logic.
    *
    * @param options
    *   options for how the forceReconnect works
    * @return
    *   an effect which can be failed with `java.io.IOException` when the forceReconnect fails or `InterruptedException` when the connection
    *   is not connected
    */
  def forceReconnect(options: ForceReconnectOptions): F[Unit]

  /** Calculates the round trip time between this client and the server.
    *
    * @return
    *   an effect for the RTT as a duration which can be failed with `java.io.IOException` if various IO exception such as timeout or
    *   interruption
    */
  def RTT: F[FiniteDuration]

  /** Get the number of messages in the outgoing queue for this connection. This value is volatile in the sense that it changes often and
    * may be adjusted by more than one message. It changes every time a message is published (put in the outgoing queue) and every time a
    * message is removed from the queue to be written over the socket
    *
    * @return
    *   an effect with the number of messages in the outgoing queue
    */
  def outgoingPendingMessageCount: F[Long]

  /** Get the number of bytes based to be written calculated from the messages in the outgoing queue for this connection. This value is
    * volatile in the sense that it changes often and may be adjusted by more than one message's bytes. It changes every time a message is
    * published (put in the outgoing queue) and every time a message is removed from the queue to be written over the socket
    * @return
    *   an effect with the number of messages in the outgoing queue
    */
  def outgoingPendingBytes: F[Long]

}
