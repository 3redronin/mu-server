package io.muserver

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.Executors

internal class Mu3ServerImpl(
    private val acceptors: List<ConnectionAcceptor>,
    val handlers: List<MuHandler>,
    private val responseCompleteListeners: MutableList<ResponseCompleteListener>,
    val exceptionHandler: UnhandledExceptionHandler,
) : MuServer {

    val statsImpl = Mu3StatsImpl()

    private fun startListening() {
        if (acceptors.isEmpty()) throw IllegalStateException("No listener ports defined")
        for (acceptor in acceptors) {
            acceptor.start()
        }
    }

    override fun stop() {
        for (acceptor in acceptors) {
            acceptor.stop(10000)
        }
    }

    override fun uri(): URI {
        return httpsUri() ?: httpUri() ?: /* not possible: */ throw IllegalStateException("No URI")
    }

    override fun httpUri(): URI? {
        return acceptors.firstOrNull { !it.isHttps }?.uri
    }

    override fun httpsUri(): URI? {
        return acceptors.firstOrNull { it.isHttps }?.uri
    }

    override fun stats(): MuStats = statsImpl

    override fun activeConnections(): Set<HttpConnection> {
        return setOf()
    }

    override fun address(): InetSocketAddress {
        return acceptors.first().address
    }

    override fun minimumGzipSize(): Long {
        return 0
    }

    override fun maxRequestHeadersSize(): Int {
        return 0
    }

    override fun requestIdleTimeoutMillis(): Long {
        return 0
    }

    override fun maxRequestSize(): Long {
        return 0
    }

    override fun maxUrlSize(): Int {
        return 0
    }

    override fun gzipEnabled(): Boolean {
        return false
    }

    override fun mimeTypesToGzip(): Set<String> {
        return setOf()
    }

    override fun changeHttpsConfig(newHttpsConfig: HttpsConfigBuilder) {
    }

    override fun sslInfo(): SSLInfo? {
        return null
    }

    override fun rateLimiters(): List<RateLimiter> {
        return listOf()
    }

    fun onRequestStarted(req: Mu3Request) {
        statsImpl.onRequestStarted(req)
    }

    fun onRequestEnded(req: Mu3Request, resp: Mu3Response) {
        statsImpl.onRequestEnded(req)
        for (listener in responseCompleteListeners) {
            listener.onComplete(resp)
        }
    }

    companion object {
        @JvmStatic
        fun start(builder: MuServerBuilder): MuServer {

            val exceptionHandler = UnhandledExceptionHandler.getDefault(builder.unhandledExceptionHandler())

            val executor = builder.executor() ?: Executors.newCachedThreadPool()
            val acceptors = mutableListOf<ConnectionAcceptor>()
            val impl = Mu3ServerImpl(acceptors, builder.handlers(), builder.responseCompleteListeners(), exceptionHandler)
            val address = builder.interfaceHost()?.let { InetAddress.getByName(it) }
            if (builder.httpsPort() >= 0) {
                val httpsConfig = (builder.httpsConfigBuilder() ?: HttpsConfigBuilder.unsignedLocalhost()).build3()
                acceptors.add(ConnectionAcceptor.create(impl, address, builder.httpsPort(), httpsConfig, executor))
            }
            if (builder.httpPort() >= 0) {
                acceptors.add(ConnectionAcceptor.create(impl, address, builder.httpPort(), null, executor))
            }
            impl.startListening()
            return impl
        }
    }

}

internal fun Closeable.closeQuietly() {
    try {
        this.close()
    } catch (_: IOException) {
    }
}

