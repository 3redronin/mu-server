package io.muserver

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.net.*
import java.util.HashMap
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
    private val executorService: ExecutorService,
) {

    private enum class State { NOT_STARTED, STARTED, STOPPING, STOPPED }

    private val connections: KeySetView<Mu3Http1Connection, Boolean> = ConcurrentHashMap.newKeySet()


    @Volatile
    private var state = State.NOT_STARTED

    val isHttps = httpsConfig != null

    private val thread = Thread( {
        while (state == State.STARTED) {
            try {
                val clientSocket = socketServer.accept()
                executorService.submit {
                    var socket = clientSocket
                    if (httpsConfig != null) {

                        val ssf = httpsConfig.sslContext().socketFactory
                        val secureSocket = ssf.createSocket(socket, null, socket.port, true) as SSLSocket
                        secureSocket.useClientMode = false
                        secureSocket.addHandshakeCompletedListener { listener ->
                            log.info("Handshake complete ${listener}")
                        }
                        secureSocket.startHandshake()
                        socket = secureSocket

                    }
                    val con = Mu3Http1Connection(server, this, socket)
                    connections.add(con)
                    con.start(socket.getOutputStream())
                }
            } catch (e: Exception) {
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
        log.info("Closing server")
        socketServer.close()
        log.info("Closed")
    }, toString())

    fun start() {
        if (state != State.STOPPED && state != State.NOT_STARTED) throw IllegalStateException("Cannot start with state $state")
        thread.isDaemon = false
        state = State.STARTED
        thread.start()
    }

    fun stop(timeoutMillis: Long) {
        log.info("Stopping server 1")
        state = State.STOPPING
        socketServer.close()
        thread.join(timeoutMillis)
        if (thread.isAlive) {
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
            executor: ExecutorService
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


            val uri = URI("http" + (if (httpsConfig == null) "" else "s") + "://localhost:" + socketServer.localPort)
            return ConnectionAcceptor(server, socketServer, socketServer.localSocketAddress as InetSocketAddress, uri, httpsConfig, executor)
        }
    }

}