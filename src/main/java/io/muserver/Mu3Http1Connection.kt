package io.muserver

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.Certificate
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSocket

internal class Mu3Http1Connection(
    val server: Mu3ServerImpl,
    private val creator: ConnectionAcceptor,
    private val clientSocket: Socket,
    val startTime: Instant,
    private val clientCert : Certificate?
) : HttpConnection {
    private val requestPipeline : Queue<HttpRequestTemp> = ConcurrentLinkedQueue()
    private val remoteAddress = clientSocket.remoteSocketAddress as InetSocketAddress
    private val localAddress = clientSocket.localSocketAddress as InetSocketAddress
    private val currentRequest = AtomicReference<Any?>()
    private val completedRequests = AtomicLong()
    @Volatile
    private var lastIO = System.currentTimeMillis()
    private val invalidHttpRequests = AtomicLong(0)
    private val rejectedDueToOverload = AtomicLong(0)
    private val closed = AtomicBoolean(false)

    private val requestTimeout = if (server.requestIdleTimeoutMillis() >= Int.MAX_VALUE) {
        Int.MAX_VALUE
    } else {
        server.requestIdleTimeoutMillis().toInt()
    }

    fun start(outputStream: OutputStream) {

        try {
            RawRequestInputStream(this, clientSocket.getInputStream()).use { reqStream ->
                val requestParser = Http1MessageParser(HttpMessageType.REQUEST, requestPipeline, reqStream, server.maxRequestHeadersSize(), server.maxUrlSize())
                var closeConnection = false
                while (!closeConnection) {
                    val msg = try {
                        requestParser.readNext()
                    } catch (e: IOException) {
                        log.info("Error reading from client input stream ${e.javaClass} ${e.message}")
                        break
                    }
                    if (msg == EOFMsg) {
                        log.info("EOF detected")
//                    reqStream.closeQuietly() // TODO: confirm if the input stream should be closed
                        clientSocket.shutdownInput()
                        break
                    }
                    val request = msg as HttpRequestTemp

                    var rejectException = request.rejectRequest
                    val relativeUrl = try {
                        request.normalisedUri()
                    } catch (e: HttpException) {
                        rejectException = rejectException ?: e
                        "/"
                    }

                    val serverUri = creator.uri.resolve(relativeUrl)
                    val requestUri = Headtils.getUri(log, request.headers(), relativeUrl, serverUri)
                    val muRequest = Mu3Request(
                        connection = this,
                        method = request.method!!,
                        requestUri = requestUri,
                        serverUri = serverUri,
                        httpVersion = request.httpVersion!!,
                        mu3Headers = request.headers(),
                        bodySize = request.bodySize!!,
                        body = if (request.bodySize == BodySize.NONE) EmptyInputStream.INSTANCE else Http1BodyStream(
                            requestParser, server.maxRequestBodySize
                        )
                    )
                    clientSocket.soTimeout = requestTimeout

                    val muResponse = Mu3Response(muRequest, outputStream)
                    muRequest.response = muResponse
                    closeConnection = muRequest.headers().closeConnection(muRequest.httpVersion)


                    if (rejectException == null) {
                        var first : RateLimitRejectionAction? = null
                        for (rateLimiter in server.rateLimiters) {
                            val action = rateLimiter.record(muRequest)
                            if (action != null && first == null) {
                                first = action
                            }
                        }
                        if (first != null) {
                            if (first == RateLimitRejectionAction.SEND_429) {
                                rejectException = HttpException(HttpStatus.TOO_MANY_REQUESTS_429)
                                rejectException.responseHeaders().setAll(request.headers())
                            }
                        }
                    }

                    if (rejectException != null) {
                        onInvalidRequest(rejectException)
                        muResponse.status(rejectException.status())
                        muResponse.headers().set(rejectException.responseHeaders())
                        if (rejectException.message != null) {
                            muResponse.write(rejectException.message!!)
                        }
                        closeConnection = cleanUpNicely(closeConnection, muResponse, muRequest)
                    } else {

                        onRequestStarted(muRequest)

                        try {
                            log.info("Got request: $muRequest")
                            try {
                                var handled = false
                                for (handler in server.handlers) {
                                    if (handler.handle(muRequest, muResponse)) {
                                        handled = true
                                        break
                                    }
                                }
                                if (!handled) throw HttpException(
                                    HttpStatus.NOT_FOUND_404,
                                    "This page is not available. Sorry about that."
                                )

                                if (muRequest.isAsync) {
                                    val asyncHandle = muRequest.asyncHandle!!
                                    // TODO set proper timeout
                                    asyncHandle.waitForCompletion(Long.MAX_VALUE)
                                }

                            } catch (e: Exception) {
                                if (muResponse.hasStartedSendingData()) {
                                    // can't write a custom error at this point
                                    throw e
                                } else {
                                    server.exceptionHandler.handle(muRequest, muResponse, e)
                                }
                            }
                            closeConnection = cleanUpNicely(closeConnection, muResponse, muRequest)
                        } catch (e: Exception) {
                            closeConnection = true
                            log.warn("Unrecoverable error for $muRequest", e)
                            muResponse.state = ResponseState.ERRORED
                        } finally {
                            onRequestEnded(muRequest, muResponse)
                            clientSocket.soTimeout = 0
                        }
                        val websocket: WebsocketConnection? = muResponse.websocket
                        if (!closeConnection && websocket != null) {
                            currentRequest.set(websocket)
                            websocket.runAndBlockUntilDone(clientSocket.getInputStream(), outputStream, requestParser.readBuffer)
                            closeConnection = true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            log.error("Unhandled error at the socket", e)
        } finally {
            clientSocket.closeQuietly()
        }
    }

    private fun onInvalidRequest(rejectException: HttpException) {
        if (rejectException.status() == HttpStatus.TOO_MANY_REQUESTS_429) {
            rejectedDueToOverload.incrementAndGet()
            server.statsImpl.onRejectedDueToOverload()
        } else {
            invalidHttpRequests.incrementAndGet()
            server.statsImpl.onInvalidRequest()
        }
    }

    private fun cleanUpNicely(
        closeConnection: Boolean,
        muResponse: Mu3Response,
        muRequest: Mu3Request
    ): Boolean {
        var reallyClose = closeConnection
        if (!reallyClose) {
            reallyClose = muResponse.headers().closeConnection(muRequest.httpVersion())
        }
        muRequest.cleanup(muResponse.statusValue())
        muResponse.cleanup()
        return reallyClose
    }


    private fun onRequestStarted(req: Mu3Request) {
        currentRequest.set(req)
        server.onRequestStarted(req)
    }

    private fun onRequestEnded(req: Mu3Request, resp: Mu3Response) {
        currentRequest.set(null)
        completedRequests.incrementAndGet()
        for (listener in resp.completionListeners()) {
            listener.onComplete(resp)
        }
        server.onRequestEnded(req, resp)
    }

    override fun toString() = "HTTP1 connection from $remoteAddress to $localAddress"

    override fun httpVersion(): HttpVersion = HttpVersion.HTTP_1_1

    override fun idleTimeMillis(): Long = System.currentTimeMillis() - lastIO

    override fun isHttps() = creator.isHttps

    override fun httpsProtocol(): String? {
        return if (clientSocket is SSLSocket) {
            clientSocket.session.protocol
        } else null
    }

    override fun cipher(): String? {
        return if (clientSocket is SSLSocket) {
            clientSocket.session.cipherSuite
        } else null
    }

    override fun startTime() = startTime

    override fun remoteAddress() = remoteAddress

    override fun completedRequests() = completedRequests.get()

    override fun invalidHttpRequests() = invalidHttpRequests.get()

    override fun rejectedDueToOverload() = rejectedDueToOverload.get()

    override fun activeRequests(): Set<MuRequest> {
        val cur = currentRequest.get()
        return if (cur == null || cur !is MuRequest) emptySet() else setOf(cur)
    }

    override fun activeWebsockets(): Set<MuWebSocket> {
        val cur = currentRequest.get()
        return if (cur == null || cur !is MuWebSocket) emptySet() else setOf(cur)
    }

    override fun server() = server

    override fun clientCertificate() = Optional.ofNullable(clientCert)

    fun isClosed() = closed.get()
    override fun abort() {
        if (closed.compareAndSet(false, true)) {
            val cur = currentRequest.get()
            if (cur != null && cur is Mu3Request) {
                cur.asyncHandle?.complete(MuException("Connection aborted"))
            }
            clientSocket.close()
        }
    }
    fun abortWithTimeout() {
        if (closed.compareAndSet(false, true)) {
            val cur = currentRequest.get()
            if (cur != null) {
                if (cur is Mu3Request) {
                    cur.asyncHandle?.complete(TimeoutException("Idle timeout exceeded"))
                } else if (cur is WebsocketConnection) {
                    cur.onTimeout()
                }
            }
            clientSocket.close()
        }
    }

    override fun isIdle() = activeRequests().isEmpty() && activeWebsockets().isEmpty()

    fun onBytesRead(read: Int) {
        onIO()
        server.statsImpl.onBytesRead(read.toLong())
    }

    fun onBytesRead(buffer: ByteArray, off: Int, len: Int) {
        onIO()
        server.statsImpl.onBytesRead(len.toLong())
    }

    private fun onIO() {
        lastIO = System.currentTimeMillis()
    }

    fun lastIO() = lastIO

    companion object {
        private val log: Logger = LoggerFactory.getLogger(Mu3Http1Connection::class.java)
    }
}

