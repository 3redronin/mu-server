package io.muserver

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.net.*
import java.nio.charset.StandardCharsets
import java.security.cert.Certificate
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentHashMap.KeySetView
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket

internal class ConnectionAcceptor(
    private val server: Mu3ServerImpl,
    private val socketServer: ServerSocket,
    val address: InetSocketAddress,
    val uri: URI,
    @Volatile
    var httpsConfig: HttpsConfig?,
    val http2Config: Http2Config?,
    private val executorService: ExecutorService,
    val contentEncoders: List<ContentEncoder>,
) {

    private enum class State { NOT_STARTED, STARTED, STOPPING, STOPPED }

    private val connections: KeySetView<BaseHttpConnection, Boolean> = ConcurrentHashMap.newKeySet()

    fun activeConnections() : Set<HttpConnection> = connections


    @Volatile
    private var state = State.NOT_STARTED

    val isHttps = httpsConfig != null

    private val acceptorThread = Thread( {
        while (state == State.STARTED) {
            try {
                val clientSocket = socketServer.accept()
                val startTime = Instant.now()
                try {
                    executorService.submit {
                        val h2 = http2Config?.enabled() == true
                        handleClientSocket(clientSocket, startTime, h2, false)
                    }
                } catch (e: RejectedExecutionException) {
                    val oldTimeout = clientSocket.soTimeout
                    try {
                        // Send a 503, blocking the acceptor thread. We can't schedule this on the executor. We are
                        // so overloaded it's good to have a pause here. But still have a tight timeout so a really
                        // slow consumer doesn't hold things up.
                        clientSocket.soTimeout = 2000
                        handleClientSocket(clientSocket, startTime, false, true)
                    } catch (e2: Exception) {
                        log.info("Exception while writing 503 when executor is full: ${e.message}")
                    } finally {
                        clientSocket.soTimeout = oldTimeout
                    }
                }
            } catch (e: Throwable) {
                if (Thread.interrupted() || e is SocketException) {
                    log.info("Accept listening stopped")
                } else {
                    log.info("Exception when state=$state", e)
                    if (state == State.STARTED) {
                        log.warn("Error while accepting from $this", e)
                    }
                }
            }
        }
        log.info("Closing server with ${connections.size} connected connections")
        val waitUntil = Instant.now().plusSeconds(20)
        for (connection in connections) {
            connection.initiateGracefulShutdown()
        }
        while (connections.isNotEmpty() && waitUntil.isAfter(Instant.now())) {
            Thread.sleep(10)
        }
        for (connection in connections) {
            log.info("Force closure of active connection $connection with requests ${connection.activeRequests()}")
            connection.abort()
        }
        socketServer.close()
        log.info("Closed")
    }, toString())

    private fun handleClientSocket(clientSocket: Socket, startTime: Instant, http2Enabled: Boolean, rejectDueToOverload: Boolean) {
        var socket = clientSocket
        var clientCert : Certificate? = null

        val hc = httpsConfig
        var httpVersion = HttpVersion.HTTP_1_1
        if (hc != null) {
            try {
                val ssf = hc.sslContext().socketFactory
                val secureSocket = ssf.createSocket(socket, null, socket.port, true) as SSLSocket
                secureSocket.useClientMode = false
                secureSocket.enabledProtocols = hc.protocolsArray()
                secureSocket.enabledCipherSuites = hc.cipherSuitesArray()

                if (http2Enabled) {
                    val sslParams = secureSocket.sslParameters
                    sslParams.applicationProtocols = arrayOf("h2", "http/1.1")
                    secureSocket.sslParameters = sslParams
                }
                val clientAuthTrustManager = hc.clientAuthTrustManager()
                secureSocket.wantClientAuth = clientAuthTrustManager != null

                secureSocket.addHandshakeCompletedListener { event ->
                    log.info("Handshake complete $event")
                }
                secureSocket.startHandshake()
                log.info("Selected protocol is ${secureSocket.applicationProtocol}")
                if (secureSocket.applicationProtocol == "h2") {
                    httpVersion = HttpVersion.HTTP_2
                }

                if (clientAuthTrustManager != null) {
                    try {
                        clientCert = secureSocket.session.peerCertificates?.firstOrNull()
                    } catch (ignored: SSLPeerUnverifiedException) {
                    }
                }

                socket = secureSocket
            } catch (e: Exception) {
                log.warn("Failed TLS handshaking", e)
                server.statsImpl.onFailedToConnect()
                return
            }
        }

        if (rejectDueToOverload) {
            server.statsImpl.onRejectedDueToOverload()
            socket.soTimeout = 2000
            socket.getInputStream().use { inputStream ->
                socket.getOutputStream().use { os ->
                    os.write(serverUnavailableResponse)
                    os.flush()
                    val buf = ByteArray(1024)
                    var reads = 0
                    while (inputStream.read(buf) != -1 && reads < 10) {
                        reads++
                        log.info("read $reads")
                    }
                    log.info("Reads done "+ reads);
                }
            }
        } else {

            val con: BaseHttpConnection = if (httpVersion == HttpVersion.HTTP_2)
                Http2Connection(server, this, socket, clientCert, startTime, http2Config!!.initialSettings(), executorService)
            else Http1Connection(server, this, socket, clientCert, startTime)


            connections.add(con)
            server.statsImpl.onConnectionOpened(con)
            try {
                socket.getOutputStream().use { clientOut ->
                    HttpConnectionInputStream(con, socket.getInputStream()).use { clientIn ->
                        con.start(clientIn, clientOut)
                    }
                }
            } catch (t: Throwable) {
                log.error("Unhandled exception for $con", t)
            } finally {
                server.statsImpl.onConnectionClosed(con)
                connections.remove(con)
            }
        }
    }

    private val timeoutThread = if (server.idleTimeoutMillis() == 0L) null else Thread( {
        while (state == State.STARTED) {
            try {
                val cutoff = System.currentTimeMillis() - server.idleTimeoutMillis()
                for (con in connections) {
                    if (con.lastIO() < cutoff) {
                        log.info("Timing out " + con)
                        con.abortWithTimeout()
                    }
                }
                Thread.sleep(200)
            } catch (t: Throwable) {
                if (state == State.STARTED) {
                    log.error("Exception while doing timeouts", t)
                }
            }
        }
    }, toString() + "-watcher")

    fun start() {
        if (state != State.STOPPED && state != State.NOT_STARTED) throw IllegalStateException("Cannot start with state $state")
        acceptorThread.isDaemon = false
        state = State.STARTED
        acceptorThread.start()
        timeoutThread?.start()
    }

    fun stop(timeoutMillis: Long) {
        log.info("Stopping server 1")
        state = State.STOPPING
        timeoutThread?.interrupt()
        socketServer.close()
        acceptorThread.join(timeoutMillis)
        if (acceptorThread.isAlive) {
            log.warn("Could not kill $this after $timeoutMillis ms")
        }
        state = State.STOPPED
    }

    override fun toString() = "mu-acceptor-${address.port}"
    fun changeHttpsConfig(newHttpsConfig: HttpsConfig) {
        newHttpsConfig.setHttpsUri(uri)
        this.httpsConfig  = newHttpsConfig;
    }

    companion object {
        private val log : Logger = LoggerFactory.getLogger(ConnectionAcceptor::class.java)
        fun create(
            server: Mu3ServerImpl,
            address: InetAddress?,
            bindPort: Int,
            httpsConfig: HttpsConfig?,
            http2Config: Http2Config?,
            executor: ExecutorService,
            contentEncoders: List<ContentEncoder>
        ): ConnectionAcceptor {
            val socketServer = ServerSocket(bindPort, 50, address)
            val supportedOptions: Set<SocketOption<*>> = socketServer.supportedOptions()
            val requestedOptions: Map<SocketOption<*>, *> = mapOf(
                    StandardSocketOptions.SO_REUSEADDR to true,
                    StandardSocketOptions.SO_REUSEPORT to true,
                    )
            val appliedOptions: MutableMap<SocketOption<*>, Any> = HashMap()
            for ((key1, value1) in requestedOptions) {
                val key = key1 as SocketOption<in Any>
                if (supportedOptions.contains(key)) {
                    val value = value1!!
                    socketServer.setOption(key, value)
                    appliedOptions[key] = value
                }
            }
            for ((key, value) in appliedOptions) {
                log.info("Applied socket option $key=$value")
            }


            val uriHost = address?.hostName ?: "localhost"

            val uri = URI("http" + (if (httpsConfig == null) "" else "s") + "://$uriHost:" + socketServer.localPort)
            return ConnectionAcceptor(server, socketServer, socketServer.localSocketAddress as InetSocketAddress, uri, httpsConfig, http2Config, executor, contentEncoders)
        }

        val serverUnavailableResponse = ("HTTP/1.1 503 Service Unavailable\r\n" +
                "connection: close\r\n" +
                "content-type: text/plain;charset=utf-8\r\n" +
                "content-length: 23\r\n" +
                "\r\n" +
                "503 Service Unavailable").toByteArray(StandardCharsets.US_ASCII)

    }

}