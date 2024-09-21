package io.muserver

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.net.*
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentHashMap.KeySetView
import java.util.concurrent.ExecutorService
import javax.net.ssl.SSLSocket

internal class ConnectionAcceptor(
    private val server: Mu3ServerImpl,
    private val socketServer: ServerSocket,
    val address: InetSocketAddress,
    val uri: URI,
    val httpsConfig: HttpsConfig?,
    val http2Config: Http2Config?,
    val executorService: ExecutorService,
    val contentEncoders: List<ContentEncoder>,
) {

    private enum class State { NOT_STARTED, STARTED, STOPPING, STOPPED }

    private val connections: KeySetView<Mu3Http1Connection, Boolean> = ConcurrentHashMap.newKeySet()


    @Volatile
    private var state = State.NOT_STARTED

    val isHttps = httpsConfig != null

    private val acceptorThread = Thread( {
        while (state == State.STARTED) {
            try {
                val clientSocket = socketServer.accept()
                executorService.submit {
                    handleClientSocket(clientSocket)
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
        while (connections.isNotEmpty() && waitUntil.isBefore(Instant.now())) {
            for (connection in connections) {
                if (connection.isIdle) {
                    log.info("Closing idle connection $connection")
                    connection.abort()
                }
            }
            Thread.sleep(10)
        }
        for (connection in connections) {
            log.info("Force closure of active connection $connection with requests ${connection.activeRequests()}")
            connection.abort()
        }
        socketServer.close()
        log.info("Closed")
    }, toString())

    private fun handleClientSocket(clientSocket: Socket) {
        val startTime = Instant.now()
        var socket = clientSocket
        if (httpsConfig != null) {
            try {
                val ssf = httpsConfig.sslContext().socketFactory
                val secureSocket = ssf.createSocket(socket, null, socket.port, true) as SSLSocket
                secureSocket.useClientMode = false

                if (http2Config?.enabled == true) {
                    val sslParams = secureSocket.sslParameters
                    // TODO: advertise h2
//                    sslParams.applicationProtocols = arrayOf("h2", "http/1.1")
                    sslParams.applicationProtocols = arrayOf("http/1.1")
                    secureSocket.sslParameters = sslParams
                }

                secureSocket.addHandshakeCompletedListener { event ->
                    log.info("Handshake complete $event")
                }
                secureSocket.startHandshake()
                log.info("Selected protocol is ${secureSocket.applicationProtocol}")
                socket = secureSocket
            } catch (e: Exception) {
                log.warn("Failed TLS handshaking", e)
                server.statsImpl.onFailedToConnect()
                return
            }
        }
        val con = Mu3Http1Connection(server, this, socket, startTime)
        connections.add(con)
        server.statsImpl.onConnectionOpened(con)
        try {
            socket.getOutputStream().use { clientOut ->
                con.start(clientOut)
            }
        } catch (t: Throwable) {
            log.error("Unhandled exception for $con", t)
        } finally {
            server.statsImpl.onConnectionClosed(con)
            connections.remove(con)
        }
    }

    private val timeoutThread = Thread( {
        while (state == State.STARTED) {
            try {
                val cutoff = System.currentTimeMillis() - server.idleTimeoutMillis()
                for (con in connections) {
                    if (con.lastIO() < cutoff) {
                        log.info("Aborting it")
                        con.abort()
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
        timeoutThread.start()
    }

    fun stop(timeoutMillis: Long) {
        log.info("Stopping server 1")
        state = State.STOPPING
        timeoutThread.interrupt()
        socketServer.close()
        acceptorThread.join(timeoutMillis)
        if (acceptorThread.isAlive) {
            log.warn("Could not kill $this after $timeoutMillis ms")
        }
        state = State.STOPPED
    }

    override fun toString() = "mu-acceptor-${address.port}"

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
    }

}