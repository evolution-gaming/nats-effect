package com.evolution.natseffect

import cats.effect.{Async, Resource}
import cats.syntax.all.*
import com.evolution.natseffect.impl.{CompletableFutureOps, OptionsOps, WrappedConnection}
import io.nats.client.Options.DEFAULT_URL
import io.nats.client.{AuthHandler, Connection as JConnection, Nats as JNats, Options as JOptions}

import scala.jdk.DurationConverters.ScalaDurationOps

/** The Nats class is the entry point into the NATS client for Scala. This class is used to create a connection to the NATS server.
  *
  * <p>Simple connections can be created with a URL, while more control is provided when an [[Options]] object is used. There are a number
  * of options that effect every connection, as described in the [[Options]] documentation.
  *
  * <p>At its simplest, you can connect to a nats-server on the local host using the default port with: <pre>Nats.connect()</pre> <p>and
  * start sending or receiving messages immediately after that.
  *
  * <p>While the simple case relies on a single URL, the options allows you to configure a list of servers that is used at connect time and
  * during reconnect scenarios.
  *
  * <p>NATS supports TLS connections. This library relies on the standard SSLContext class to configure SSL certificates and trust managers,
  * as a result there are two steps to setting up a TLS connection, configuring the SSL context and telling the library which one to use.
  * Several options are provided for each. To tell the library to connect with TLS:
  *
  * <ul> <li>Pass a tls:// URL to the connect method, or as part of the options. The library will use the default SSLContext for both the
  * client certificates and trust managers. <li>Set the [[Options.useDefaultTls]] flag to 'true', again the default SSL Context is used.
  * <li>Set the [[Options.sslContext]] field when building your options. Your context will be used. <li>Pass an opentls:// url to the
  * connect method, or in the options. The library will create a special SSLContext that has no client certificates and trusts any server.
  * <strong>This is less secure, but useful for testing and behind a firewall.</strong> <li>Set the [[Options.useTrustAllTls]] flag to
  * 'true' when creating your options, again the all trusting, non-verifiable client is created. </ul>
  *
  * <p>To set up the default context for tls:// or [[Options.useDefaultTls]] = 'true' you can: <ul> <li>Configure the default using System
  * properties, i.e. <em>javax.net.ssl.keyStore</em>. <li>Set the context manually with the SSLContext setDefault method. </ul>
  *
  * <p>If the server is configured to verify clients, the opentls mode will not work, and the other modes require a client certificate to
  * work.
  *
  * <p>Authentication, if configured on the server, is managed via the Options as well. However, the url passed to `Nats.connect(String)``
  * can provide a user/password pair or a token using the forms: {@code nats://user:password@server:port} and
  * {@code nats://token@server:port} .
  *
  * <p>Regardless of the method used a [[Connection]] object is created, and provides the methods for sending, receiving and dispatching
  * messages.
  */
object Nats {

  /** Connect to specific url, with all the default options and return a `Resource` when a connection is ready. The connection is closed
    * when the resource and released. It can be drained instead if [[Options.drainTimeout]] is specified. The Scala client generally expects
    * URLs of the form {@code nats://hostname:port}
    *
    * <p>but also allows urls with a user password {@code nats://user:pass@hostname:port} .</p>
    *
    * <p>or token in them {@code nats://token@hostname:port} .</p>
    *
    * <p>Moreover, you can initiate a TLS connection, by using the `tls` schema, which will use the default SSLContext, or fail if one is
    * not set. For testing and development, the `opentls` schema is support when the server is in non-verify mode. In this case, the client
    * will accept any server certificate and will not provide one of its own.</p>
    *
    * <p>This is a synchronous call, and the connection should be ready for use on return there are network timing issues that could result
    * in a successful connect call but the connection is invalid soon after return, where soon is in the network/thread world.</p>
    *
    * <p>If the connection fails, an IOException is thrown</p>
    *
    * @param url
    *   comma separated list of the URLs of the server, i.e. nats://localhost:4222,nats://localhost:4223
    * @return
    *   the Resource for created Connection which can be failed with `java.io.IOException` if if a networking issue occurs or
    *   `InterruptedException` if the current thread is interrupted
    */
  def connect[F[_]: Async](url: String = DEFAULT_URL): Resource[F, Connection[F]] =
    connect(Options[F]().withNatsServerUris(Vector(url)))

  /** Options can be used to set the server URL, or multiple URLS, callback handlers for various errors, and connection events.
    *
    * <p>This is a synchronous call, and the connection should be ready for use on return there are network timing issues that could result
    * in a successful connect call but the connection is invalid soon after return, where soon is in the network/thread world.
    *
    * <p>If the connection fails, an IOException is thrown
    *
    * <p>As of 2.6 the connect call with throw an io.nats.AuthenticationException if an authentication error occurred during connect, and
    * the connect failed. Because multiple servers are tried, this exception may not indicate a problem on the "last server" tried, only
    * that all the servers were tried and at least one failed because of authentication. In situations with heterogeneous authentication for
    * multiple servers you may need to use an ErrorListener to determine which one had the problem. Authentication failures are not
    * immediate connect failures because of the server list, and the existing 2.x API contract.
    *
    * <p>As of 2.6.1 authentication errors play an even stronger role. If a server returns an authentication error twice without a
    * successful connection, the connection is closed. This will require a reconnect scenario, since the initial connection only tries each
    * server one time. However, if you have two servers S1 and S2, and S1 returns and authentication error on connect, but S2 succeeds.
    * Later, if S2 fails and S1 returns the same error the connection will be closed. However, if S1 succeeds on reconnect the "last error"
    * will be cleared so it would be allowed to fail again in the future.
    *
    * @param options
    *   the options object to use to create the connection
    * @return
    *   the Resource for the new Connection which can be failed with `java.io.IOException` if a networking issue occurs or *
    *   `InterruptedException` if the current thread is interrupted
    */
  def connect[F[_]: Async](options: Options[F]): Resource[F, Connection[F]] = {
    def doConnect(javaOptions: JOptions) =
      if (options.reconnectOnConnect) JNats.connectReconnectOnConnect(javaOptions) else JNats.connect(javaOptions)

    def doDisconnect(connection: JConnection) =
      Async[F].delay(connection.getStatus).flatMap { connectionStatus =>
        options.drainTimeout match {
          case Some(value) if connectionStatus != JConnection.Status.CLOSED =>
            for {
              drained <- connection.drain(value.toJava).toF
              _       <- new RuntimeException(s"Connection is not fully drained within $value: $connection").raiseError.unlessA(drained)
            } yield ()
          case _ => Async[F].blocking(connection.close())
        }
      }

    for {
      javaOptions    <- options.asJava
      javaConnection <- Resource.make(Async[F].blocking(doConnect(javaOptions)))(doDisconnect)
    } yield new WrappedConnection[F](javaConnection, options)
  }

  /** This method works exactly as `io.nats.client.Nats.credentials(String)`, but returns an effect instead.
    */
  def credentials[F[_]: Async](credsFile: String): F[AuthHandler] = Async[F].blocking(JNats.credentials(credsFile))

  /** This method works exactly as `io.nats.client.Nats.credentials(String, String)`, but returns an effect instead.
    */
  def credentials[F[_]: Async](jwtFile: String, nkeyFile: String): F[AuthHandler] = Async[F].blocking(JNats.credentials(jwtFile, nkeyFile))

  /** This method works exactly as `io.nats.client.Nats.staticCredentials(Array[Byte])`, but returns an effect instead.
    */
  def staticCredentials(credsBytes: Array[Byte]): AuthHandler = JNats.staticCredentials(credsBytes)

  /** This method works exactly as `io.nats.client.Nats.staticCredentials(Array[Byte], Array[Byte])`, but returns an effect instead.
    */
  def staticCredentials(jwt: Array[Char], nkey: Array[Char]): AuthHandler = JNats.staticCredentials(jwt, nkey)

}
