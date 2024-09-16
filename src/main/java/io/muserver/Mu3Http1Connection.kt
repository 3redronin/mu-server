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
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal class Mu3Http1Connection(
    val server: Mu3ServerImpl,
    val creator: ConnectionAcceptor,
    val clientSocket: Socket,
    val startTime: Instant
) : HttpConnection {
    private val requestPipeline : Queue<HttpRequestTemp> = ConcurrentLinkedQueue()
    private val remoteAddress = clientSocket.remoteSocketAddress as InetSocketAddress
    private val currentRequest = AtomicReference<Mu3Request?>()
    private val completedRequests = AtomicLong()

    fun start(outputStream: OutputStream) {

        try {
            clientSocket.getInputStream().use { reqStream ->
                val requestParser = Http1MessageParser(HttpMessageType.REQUEST, requestPipeline, reqStream)
                while (true) {
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
                    val relativeUrl = request.normalisedUri()

                    val serverUri = creator.uri.resolve(relativeUrl)
                    val headers = request.headers()
                    val muRequest = Mu3Request(
                        connection = this,
                        method = request.method!!,
                        requestUri = serverUri,
                        serverUri = serverUri,
                        httpVersion = request.httpVersion!!,
                        mu3Headers = headers,
                        bodySize = request.bodySize!!,
                        body = if (request.bodySize == BodySize.NONE) EmptyInputStream.INSTANCE else Http1BodyStream(
                            requestParser
                        )
                    )

                    onRequestStarted(muRequest)
                    val muResponse = Mu3Response(muRequest, outputStream)
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
                            if (!handled) {
                                muResponse.status(HttpStatusCode.NOT_FOUND_404)
                                muResponse.write(HttpStatusCode.NOT_FOUND_404.toString())
                            }
                        } catch (e: Exception) {
                            // todo: decide whether to shut down the connection or not
                            handleThrownException(muResponse, e, request)
                        }
                    } finally {
                        try {
                            muRequest.body.closeQuietly()
                            try {
                                muResponse.cleanup()
                            } catch (e: HttpException) {
                                handleThrownException(muResponse, e, request)
                            }
                        } finally {
                            onRequestEnded(muRequest, muResponse)
                        }
                    }

                }

            }
        } catch (e: Exception) {
            log.error("Unhandled error at the socket", e)
            clientSocket.closeQuietly()
        }

    }

    private fun onRequestStarted(req: Mu3Request) {
        currentRequest.set(req);
        server.onRequestStarted(req)
    }

    private fun onRequestEnded(req: Mu3Request, resp: Mu3Response) {
        currentRequest.set(null)
        completedRequests.incrementAndGet()
        server.onRequestEnded(req, resp)
    }


    private fun handleThrownException(muResponse: Mu3Response, e: Exception, request: HttpRequestTemp) {
        val canSend = muResponse.state == ResponseState.NOTHING
        if (canSend) {
            val httpException = if (e is HttpException) e else {
                val errorID = "ERR-" + UUID.randomUUID()
                log.info("Sending a 500 to the client with ErrorID=$errorID for $request", e)
                HttpException(
                    HttpStatusCode.INTERNAL_SERVER_ERROR_500,
                    "Oops! An unexpected error occurred. The ErrorID=$errorID",
                    e
                )
            }
            muResponse.headers().set(httpException.responseHeaders())
            val status = httpException.status()
            muResponse.status(status)
            if (status.canHaveEntity()) {
                muResponse.write(httpException.message ?: status.toString())
            }
        } else {
            log.info(
                e.javaClass.getName() + " while handling " + request + " - note a " + muResponse.status() +
                        " was already sent and the client may have received an incomplete response. Exception was " + e.message
            )
        }
    }

    override fun toString() = "HTTP1 connection from ${clientSocket.remoteSocketAddress} to ${clientSocket.localSocketAddress}"

    override fun httpVersion(): HttpVersion = HttpVersion.HTTP_1_1

    override fun isHttps() = creator.isHttps

    override fun httpsProtocol(): String {
        TODO("Not yet implemented")
    }

    override fun cipher(): String {
        TODO("Not yet implemented")
    }

    override fun startTime() = startTime

    override fun remoteAddress() = remoteAddress

    override fun completedRequests() = completedRequests.get()

    override fun invalidHttpRequests(): Long {
        TODO("Not yet implemented")
    }

    override fun rejectedDueToOverload(): Long {
        TODO("Not yet implemented")
    }

    override fun activeRequests(): Set<MuRequest> {
        val cur = currentRequest.get()
        return if (cur == null) emptySet() else setOf(cur)
    }

    override fun activeWebsockets(): Set<MuWebSocket> {
        TODO("Not yet implemented")
    }

    override fun server() = server

    override fun clientCertificate(): Optional<Certificate> {
        TODO("Not yet implemented")
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(Mu3Http1Connection::class.java)
    }
}