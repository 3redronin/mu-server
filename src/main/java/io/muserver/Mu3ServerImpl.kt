package io.muserver

import com.danielflower.ifp.*
import com.danielflower.ifp.Http1MessageParser
import com.danielflower.ifp.HttpMessage
import com.danielflower.ifp.HttpMessageListener
import com.danielflower.ifp.HttpMessageType
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

internal class Mu3ServerImpl(private val acceptors: List<ConnectionAcceptor>, val handlers: List<MuHandler>) : MuServer {

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

    override fun stats(): MuStats? {
        return null
    }

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

    companion object {
        @JvmStatic
        fun start(builder: MuServerBuilder): MuServer {

            val executor = builder.executor() ?: Executors.newCachedThreadPool()
            val acceptors = mutableListOf<ConnectionAcceptor>()
            val impl = Mu3ServerImpl(acceptors, builder.handlers())
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

internal class Mu3Http1Connection(val server: Mu3ServerImpl, val creator: ConnectionAcceptor, val clientSocket: Socket) {
    private val requestPipeline : Queue<HttpRequest> = ConcurrentLinkedQueue()

    fun start(outputStream: OutputStream) {

        val requestListener = object : HttpMessageListener {

            override fun onHeaders(exchange: HttpMessage) {
                val request = exchange as HttpRequest
                val method = Method.valueOf(request.method)
                val serverUri = creator.uri.resolve(request.url).normalize()
                val headers = Mu3Headers(request.headers())
                val muRequest = Mu3Request(method, serverUri, serverUri, HttpVersion.fromVersion(request.httpVersion), headers)
                val muResponse = Mu3Response(muRequest, outputStream)
                log.info("Got request: $muRequest")
                for (handler in server.handlers) {
                    if (handler.handle(muRequest, muResponse)) {
                        break
                    }
                }
                muResponse.cleanup()
            }

            override fun onBodyBytes(exchange: HttpMessage, type: BodyBytesType, array: ByteArray, offset: Int, length: Int) {
                log.info("Got body bytes")
            }

            override fun onMessageEnded(exchange: HttpMessage) {
                log.info("Request ended")
            }

            override fun onError(exchange: HttpMessage, error: Exception) {
                val req = exchange as HttpRequest
                log.error("Request error: $req", error)
            }
        }

        clientSocket.getInputStream().use { reqStream ->
            val requestBuffer = ByteArray(4096)
            val requestParser = Http1MessageParser(HttpMessageType.REQUEST, requestPipeline)
            var read: Int
            try {
                while (reqStream.read(requestBuffer).also { read = it } != -1) {
                    requestParser.feed(requestBuffer, 0, read, requestListener)
                }
                log.info("Client input stream ended")
                requestParser.eof(requestListener)
            } catch (eof: EOFException) {
                log.info("EOF exception for request stream")
                requestParser.eof(requestListener)
            } catch (e: Exception) {
                requestParser.error(e, requestListener)
            }

        }


    }

    override fun toString() = "HTTP1 connection from ${clientSocket.remoteSocketAddress} to ${clientSocket.localSocketAddress}"
    companion object {
        private val log: Logger = LoggerFactory.getLogger(Mu3Http1Connection::class.java)
    }
}

private fun Closeable.closeQuietly() {
    try {
        this.close()
    } catch (_: IOException) {
    }
}
private fun Socket.shutdownInputQuietly() {
    try { this.shutdownInput() }
    catch (_: IOException) { }
}
private fun Socket.shutdownOutputQuietly() {
    try { this.shutdownOutput() }
    catch (_: IOException) { }
}
