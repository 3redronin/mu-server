package io.muserver

import io.muserver.GZIPEncoderBuilder.gzipEncoder
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.Executors

internal class Mu3ServerImpl(
    private val acceptors: List<ConnectionAcceptor>,
    val handlers: List<MuHandler>,
    private val responseCompleteListeners: MutableList<ResponseCompleteListener>,
    val exceptionHandler: UnhandledExceptionHandler,
    val maxRequestBodySize: Long,
    private val contentEncoders: List<ContentEncoder>,
    private val requestIdleTimeoutMillis: Long,
    private val idleTimeoutMillis: Long,
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

    @Deprecated("see interface")
    override fun minimumGzipSize(): Long {
        return zippy()?.minGzipSize() ?: 0L
    }

    override fun maxRequestHeadersSize(): Int {
        return 0
    }

    override fun requestIdleTimeoutMillis() = requestIdleTimeoutMillis

    override fun idleTimeoutMillis() = idleTimeoutMillis

    override fun maxRequestSize(): Long = maxRequestBodySize

    override fun maxUrlSize(): Int {
        return 0
    }

    @Deprecated("see interface")
    override fun gzipEnabled(): Boolean {
        return zippy() != null
    }

    override fun contentEncoders() = contentEncoders

    private fun zippy() = acceptors.firstOrNull()?.contentEncoders?.firstOrNull { it is GZIPEncoder } as? GZIPEncoder

    @Deprecated("see interface")
    override fun mimeTypesToGzip(): Set<String> {
        return zippy()?.mimeTypesToGzip() ?: emptySet()
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
            val actualHandlers = builder.handlers().toMutableList()
            if (builder.autoHandleExpectContinue()) {
                actualHandlers.add(0, ExpectContinueHandler(builder.maxRequestSize()))
            }

            val contentEncoders = builder.contentEncoders() ?: listOf(gzipEncoder().build())

            val impl = Mu3ServerImpl(
                acceptors = acceptors,
                handlers = actualHandlers,
                responseCompleteListeners = builder.responseCompleteListeners(),
                exceptionHandler = exceptionHandler,
                maxRequestBodySize = builder.maxRequestSize(),
                contentEncoders = contentEncoders,
                requestIdleTimeoutMillis = builder.requestReadTimeoutMillis(),
                idleTimeoutMillis = builder.idleTimeoutMills(),
            )

            val address = builder.interfaceHost()?.let { InetAddress.getByName(it) }
            if (builder.httpsPort() >= 0) {
                val httpsConfig = (builder.httpsConfigBuilder() ?: HttpsConfigBuilder.unsignedLocalhost()).build3()
                acceptors.add(ConnectionAcceptor.create(impl, address, builder.httpsPort(), httpsConfig, executor, contentEncoders))
            }
            if (builder.httpPort() >= 0) {
                acceptors.add(ConnectionAcceptor.create(impl, address, builder.httpPort(), null, executor, contentEncoders))
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

