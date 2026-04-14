package com.evolution.natseffect

import io.nats.client.Options.*
import io.nats.client.impl.SSLContextFactory
import io.nats.client.support.SSLUtils.DEFAULT_TLS_ALGORITHM
import io.nats.client.{AuthHandler, ReadListener, ReconnectDelayHandler, ServerPool, TimeTraceLogger}

import java.net.Proxy
import java.util.concurrent.{ExecutorService, ThreadFactory}
import javax.net.ssl.SSLContext
import scala.concurrent.duration.FiniteDuration
import scala.jdk.DurationConverters.JavaDurationOps

final class Options[F[_]] private (
  val natsServerUris: Seq[String] = Vector.empty,
  val reconnectOnConnect: Boolean = false,
  val noRandomize: Boolean = false,
  val reportNoResponders: Boolean = false,
  val connectionName: Option[String] = None,
  val verbose: Boolean = false,
  val pedantic: Boolean = false,
  val sslContext: Option[SSLContext] = None,
  val sslContextFactory: Option[SSLContextFactory] = None,
  val maxControlLine: Int = DEFAULT_MAX_CONTROL_LINE,
  val maxReconnects: Int = DEFAULT_MAX_RECONNECT,
  val reconnectWait: FiniteDuration = DEFAULT_RECONNECT_WAIT.toScala,
  val reconnectJitter: FiniteDuration = DEFAULT_RECONNECT_JITTER.toScala,
  val reconnectJitterTls: FiniteDuration = DEFAULT_RECONNECT_JITTER_TLS.toScala,
  val connectionTimeout: FiniteDuration = DEFAULT_CONNECTION_TIMEOUT.toScala,
  val socketReadTimeoutMillis: Int = 0,
  val socketWriteTimeout: FiniteDuration = DEFAULT_SOCKET_WRITE_TIMEOUT.toScala,
  val socketSoLinger: Int = -1,
  val pingInterval: FiniteDuration = DEFAULT_PING_INTERVAL.toScala,
  val requestCleanupInterval: FiniteDuration = DEFAULT_REQUEST_CLEANUP_INTERVAL.toScala,
  val maxPingsOut: Int = DEFAULT_MAX_PINGS_OUT,
  val reconnectBufferSize: Long = DEFAULT_RECONNECT_BUF_SIZE,
  val username: Option[Array[Char]] = None,
  val password: Option[Array[Char]] = None,
  val token: Option[Array[Char]] = None,
  val useOldRequestStyle: Boolean = false,
  val bufferSize: Int = DEFAULT_BUFFER_SIZE,
  val traceConnection: Boolean = false,
  val noEcho: Boolean = false,
  val noHeaders: Boolean = false,
  val noNoResponders: Boolean = false,
  val clientSideLimitChecks: Boolean = true,
  val supportUTF8Subjects: Boolean = false,
  val inboxPrefix: String = DEFAULT_INBOX_PREFIX,
  val maxMessagesInOutgoingQueue: Int = DEFAULT_MAX_MESSAGES_IN_OUTGOING_QUEUE,
  val ignoreDiscoveredServers: Boolean = false,
  val tlsFirst: Boolean = false,
  val useTimeoutException: Boolean = false,
  val serverPool: Option[ServerPool] = None,
  val authHandler: Option[AuthHandler] = None,
  val reconnectDelayHandler: Option[ReconnectDelayHandler] = None,
  val errorListener: Option[ErrorListener[F]] = None,
  val timeTraceLogger: Option[TimeTraceLogger] = None,
  val connectionListener: Option[ConnectionListener[F]] = None,
  val statisticsCollector: Option[StatisticsCollector[F]] = None,
  val dataPortType: Option[String] = Some(DEFAULT_DATA_PORT_TYPE),
  val proxy: Option[Proxy] = None,
  val useDefaultTls: Boolean = false,
  val useTrustAllTls: Boolean = false,
  val keystore: Option[String] = None,
  val keystorePassword: Option[Array[Char]] = None,
  val truststore: Option[String] = None,
  val truststorePassword: Option[Array[Char]] = None,
  val tlsAlgorithm: String = DEFAULT_TLS_ALGORITHM,
  val credentialPath: Option[String] = None,
  val drainTimeout: Option[FiniteDuration] = None,
  val forceFlushOnRequest: Boolean = true,
  val connectThreadFactory: Option[ThreadFactory] = None,
  val callbackThreadFactory: Option[ThreadFactory] = None,
  val readListener: Option[ReadListener] = None,
  val enableFastFallback: Boolean = false,
  val receiveBufferSize: Int = -1,
  val sendBufferSize: Int = -1,
  val subjectValidationType: SubjectValidationType = SubjectValidationType.Lenient,
  val connectExecutor: Option[ExecutorService] = None,
  val callbackExecutor: Option[ExecutorService] = None
) {

  private def copy(
    natsServerUris: Seq[String] = natsServerUris,
    reconnectOnConnect: Boolean = reconnectOnConnect,
    noRandomize: Boolean = noRandomize,
    reportNoResponders: Boolean = reportNoResponders,
    connectionName: Option[String] = connectionName,
    verbose: Boolean = verbose,
    pedantic: Boolean = pedantic,
    sslContext: Option[SSLContext] = sslContext,
    sslContextFactory: Option[SSLContextFactory] = sslContextFactory,
    maxControlLine: Int = maxControlLine,
    maxReconnects: Int = maxReconnects,
    reconnectWait: FiniteDuration = reconnectWait,
    reconnectJitter: FiniteDuration = reconnectJitter,
    reconnectJitterTls: FiniteDuration = reconnectJitterTls,
    connectionTimeout: FiniteDuration = connectionTimeout,
    socketReadTimeoutMillis: Int = socketReadTimeoutMillis,
    socketWriteTimeout: FiniteDuration = socketWriteTimeout,
    socketSoLinger: Int = socketSoLinger,
    pingInterval: FiniteDuration = pingInterval,
    requestCleanupInterval: FiniteDuration = requestCleanupInterval,
    maxPingsOut: Int = maxPingsOut,
    reconnectBufferSize: Long = reconnectBufferSize,
    username: Option[Array[Char]] = username,
    password: Option[Array[Char]] = password,
    token: Option[Array[Char]] = token,
    useOldRequestStyle: Boolean = useOldRequestStyle,
    bufferSize: Int = bufferSize,
    traceConnection: Boolean = traceConnection,
    noEcho: Boolean = noEcho,
    noHeaders: Boolean = noHeaders,
    noNoResponders: Boolean = noNoResponders,
    clientSideLimitChecks: Boolean = clientSideLimitChecks,
    supportUTF8Subjects: Boolean = supportUTF8Subjects,
    inboxPrefix: String = inboxPrefix,
    maxMessagesInOutgoingQueue: Int = maxMessagesInOutgoingQueue,
    ignoreDiscoveredServers: Boolean = ignoreDiscoveredServers,
    tlsFirst: Boolean = tlsFirst,
    useTimeoutException: Boolean = useTimeoutException,
    serverPool: Option[ServerPool] = serverPool,
    authHandler: Option[AuthHandler] = authHandler,
    reconnectDelayHandler: Option[ReconnectDelayHandler] = reconnectDelayHandler,
    errorListener: Option[ErrorListener[F]] = errorListener,
    timeTraceLogger: Option[TimeTraceLogger] = timeTraceLogger,
    connectionListener: Option[ConnectionListener[F]] = connectionListener,
    statisticsCollector: Option[StatisticsCollector[F]] = statisticsCollector,
    dataPortType: Option[String] = dataPortType,
    proxy: Option[Proxy] = proxy,
    useDefaultTls: Boolean = useDefaultTls,
    useTrustAllTls: Boolean = useTrustAllTls,
    keystore: Option[String] = keystore,
    keystorePassword: Option[Array[Char]] = keystorePassword,
    truststore: Option[String] = truststore,
    truststorePassword: Option[Array[Char]] = truststorePassword,
    tlsAlgorithm: String = tlsAlgorithm,
    credentialPath: Option[String] = credentialPath,
    drainTimeout: Option[FiniteDuration] = drainTimeout,
    forceFlushOnRequest: Boolean = forceFlushOnRequest,
    connectThreadFactory: Option[ThreadFactory] = connectThreadFactory,
    callbackThreadFactory: Option[ThreadFactory] = callbackThreadFactory,
    readListener: Option[ReadListener] = readListener,
    enableFastFallback: Boolean = enableFastFallback,
    receiveBufferSize: Int = receiveBufferSize,
    sendBufferSize: Int = sendBufferSize,
    subjectValidationType: SubjectValidationType = subjectValidationType,
    connectExecutor: Option[ExecutorService] = connectExecutor,
    callbackExecutor: Option[ExecutorService] = callbackExecutor
  ): Options[F] = new Options(
    natsServerUris,
    reconnectOnConnect,
    noRandomize,
    reportNoResponders,
    connectionName,
    verbose,
    pedantic,
    sslContext,
    sslContextFactory,
    maxControlLine,
    maxReconnects,
    reconnectWait,
    reconnectJitter,
    reconnectJitterTls,
    connectionTimeout,
    socketReadTimeoutMillis,
    socketWriteTimeout,
    socketSoLinger,
    pingInterval,
    requestCleanupInterval,
    maxPingsOut,
    reconnectBufferSize,
    username,
    password,
    token,
    useOldRequestStyle,
    bufferSize,
    traceConnection,
    noEcho,
    noHeaders,
    noNoResponders,
    clientSideLimitChecks,
    supportUTF8Subjects,
    inboxPrefix,
    maxMessagesInOutgoingQueue,
    ignoreDiscoveredServers,
    tlsFirst,
    useTimeoutException,
    serverPool,
    authHandler,
    reconnectDelayHandler,
    errorListener,
    timeTraceLogger,
    connectionListener,
    statisticsCollector,
    dataPortType,
    proxy,
    useDefaultTls,
    useTrustAllTls,
    keystore,
    keystorePassword,
    truststore,
    truststorePassword,
    tlsAlgorithm,
    credentialPath,
    drainTimeout,
    forceFlushOnRequest,
    connectThreadFactory,
    callbackThreadFactory,
    readListener,
    enableFastFallback,
    receiveBufferSize,
    sendBufferSize,
    subjectValidationType,
    connectExecutor,
    callbackExecutor
  )

  def withNatsServerUris(natsServerUris: Seq[String]): Options[F] = copy(natsServerUris = natsServerUris)

  def withReconnectOnConnect(reconnectOnConnect: Boolean): Options[F] = copy(reconnectOnConnect = reconnectOnConnect)

  def withNoRandomize(noRandomize: Boolean): Options[F] = copy(noRandomize = noRandomize)

  def withReportNoResponders(reportNoResponders: Boolean): Options[F] = copy(reportNoResponders = reportNoResponders)

  def withConnectionName(connectionName: Option[String]): Options[F] = copy(connectionName = connectionName)

  def withVerbose(verbose: Boolean): Options[F] = copy(verbose = verbose)

  def withPedantic(pedantic: Boolean): Options[F] = copy(pedantic = pedantic)

  def withSslContext(sslContext: Option[SSLContext]): Options[F] = copy(sslContext = sslContext)

  def withSslContextFactory(sslContextFactory: Option[SSLContextFactory]): Options[F] = copy(sslContextFactory = sslContextFactory)

  def withMaxControlLine(maxControlLine: Int): Options[F] = copy(maxControlLine = maxControlLine)

  def withMaxReconnects(maxReconnects: Int): Options[F] = copy(maxReconnects = maxReconnects)

  def withReconnectWait(reconnectWait: FiniteDuration): Options[F] = copy(reconnectWait = reconnectWait)

  def withReconnectJitter(reconnectJitter: FiniteDuration): Options[F] = copy(reconnectJitter = reconnectJitter)

  def withReconnectJitterTls(reconnectJitterTls: FiniteDuration): Options[F] = copy(reconnectJitterTls = reconnectJitterTls)

  def withConnectionTimeout(connectionTimeout: FiniteDuration): Options[F] = copy(connectionTimeout = connectionTimeout)

  def withSocketReadTimeoutMillis(socketReadTimeoutMillis: Int): Options[F] = copy(socketReadTimeoutMillis = socketReadTimeoutMillis)

  def withSocketWriteTimeout(socketWriteTimeout: FiniteDuration): Options[F] = copy(socketWriteTimeout = socketWriteTimeout)

  def withSocketSoLinger(socketSoLinger: Int): Options[F] = copy(socketSoLinger = socketSoLinger)

  def withPingInterval(pingInterval: FiniteDuration): Options[F] = copy(pingInterval = pingInterval)

  def withRequestCleanupInterval(requestCleanupInterval: FiniteDuration): Options[F] = copy(requestCleanupInterval = requestCleanupInterval)

  def withMaxPingsOut(maxPingsOut: Int): Options[F] = copy(maxPingsOut = maxPingsOut)

  def withReconnectBufferSize(reconnectBufferSize: Long): Options[F] = copy(reconnectBufferSize = reconnectBufferSize)

  def withUsername(username: Option[Array[Char]]): Options[F] = copy(username = username)

  def withPassword(password: Option[Array[Char]]): Options[F] = copy(password = password)

  def withToken(token: Option[Array[Char]]): Options[F] = copy(token = token)

  def withUseOldRequestStyle(useOldRequestStyle: Boolean): Options[F] = copy(useOldRequestStyle = useOldRequestStyle)

  def withBufferSize(bufferSize: Int): Options[F] = copy(bufferSize = bufferSize)

  def withTraceConnection(traceConnection: Boolean): Options[F] = copy(traceConnection = traceConnection)

  def withNoEcho(noEcho: Boolean): Options[F] = copy(noEcho = noEcho)

  def withNoHeaders(noHeaders: Boolean): Options[F] = copy(noHeaders = noHeaders)

  def withNoNoResponders(noNoResponders: Boolean): Options[F] = copy(noNoResponders = noNoResponders)

  def withClientSideLimitChecks(clientSideLimitChecks: Boolean): Options[F] = copy(clientSideLimitChecks = clientSideLimitChecks)

  def withSupportUTF8Subjects(supportUTF8Subjects: Boolean): Options[F] = copy(supportUTF8Subjects = supportUTF8Subjects)

  def withInboxPrefix(inboxPrefix: String): Options[F] = copy(inboxPrefix = inboxPrefix)

  def withMaxMessagesInOutgoingQueue(maxMessagesInOutgoingQueue: Int): Options[F] =
    copy(maxMessagesInOutgoingQueue = maxMessagesInOutgoingQueue)

  def withIgnoreDiscoveredServers(ignoreDiscoveredServers: Boolean): Options[F] = copy(ignoreDiscoveredServers = ignoreDiscoveredServers)

  def withTlsFirst(tlsFirst: Boolean): Options[F] = copy(tlsFirst = tlsFirst)

  def withUseTimeoutException(useTimeoutException: Boolean): Options[F] = copy(useTimeoutException = useTimeoutException)

  def withServerPool(serverPool: Option[ServerPool]): Options[F] = copy(serverPool = serverPool)

  def withAuthHandler(authHandler: Option[AuthHandler]): Options[F] = copy(authHandler = authHandler)

  def withReconnectDelayHandler(reconnectDelayHandler: Option[ReconnectDelayHandler]): Options[F] =
    copy(reconnectDelayHandler = reconnectDelayHandler)

  def withErrorListener(errorListener: Option[ErrorListener[F]]): Options[F] = copy(errorListener = errorListener)

  def withTimeTraceLogger(timeTraceLogger: Option[TimeTraceLogger]): Options[F] = copy(timeTraceLogger = timeTraceLogger)

  def withConnectionListener(connectionListener: Option[ConnectionListener[F]]): Options[F] = copy(connectionListener = connectionListener)

  def withStatisticsCollector(statisticsCollector: Option[StatisticsCollector[F]]): Options[F] =
    copy(statisticsCollector = statisticsCollector)

  def withDataPortType(dataPortType: Option[String]): Options[F] = copy(dataPortType = dataPortType)

  def withProxy(proxy: Option[Proxy]): Options[F] = copy(proxy = proxy)

  def withUseDefaultTls(useDefaultTls: Boolean): Options[F] = copy(useDefaultTls = useDefaultTls)

  def withUseTrustAllTls(useTrustAllTls: Boolean): Options[F] = copy(useTrustAllTls = useTrustAllTls)

  def withKeystore(keystore: Option[String]): Options[F] = copy(keystore = keystore)

  def withKeystorePassword(keystorePassword: Option[Array[Char]]): Options[F] = copy(keystorePassword = keystorePassword)

  def withTruststore(truststore: Option[String]): Options[F] = copy(truststore = truststore)

  def withTruststorePassword(truststorePassword: Option[Array[Char]]): Options[F] = copy(truststorePassword = truststorePassword)

  def withTlsAlgorithm(tlsAlgorithm: String): Options[F] = copy(tlsAlgorithm = tlsAlgorithm)

  def withCredentialPath(credentialPath: Option[String]): Options[F] = copy(credentialPath = credentialPath)

  def withDrainTimeout(drainTimeout: Option[FiniteDuration]): Options[F] = copy(drainTimeout = drainTimeout)

  def withForceFlushOnRequest(forceFlushOnRequest: Boolean): Options[F] = copy(forceFlushOnRequest = forceFlushOnRequest)

  def withConnectThreadFactory(connectThreadFactory: Option[ThreadFactory]): Options[F] = copy(connectThreadFactory = connectThreadFactory)

  def withCallbackThreadFactory(callbackThreadFactory: Option[ThreadFactory]): Options[F] =
    copy(callbackThreadFactory = callbackThreadFactory)

  def withReadListener(readListener: Option[ReadListener]): Options[F] = copy(readListener = readListener)

  def withEnableFastFallback(enableFastFallback: Boolean): Options[F] = copy(enableFastFallback = enableFastFallback)

  def withReceiveBufferSize(receiveBufferSize: Int): Options[F] = copy(receiveBufferSize = receiveBufferSize)

  def withSendBufferSize(sendBufferSize: Int): Options[F] = copy(sendBufferSize = sendBufferSize)

  def withSubjectValidationType(subjectValidationType: SubjectValidationType): Options[F] =
    copy(subjectValidationType = subjectValidationType)

  def withConnectExecutor(connectExecutor: Option[ExecutorService]): Options[F] = copy(connectExecutor = connectExecutor)

  def withCallbackExecutor(callbackExecutor: Option[ExecutorService]): Options[F] = copy(callbackExecutor = callbackExecutor)

}
object Options {
  def apply[F[_]](): Options[F] = new Options[F]()
}
