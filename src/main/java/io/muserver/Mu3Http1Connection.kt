package io.muserver

import jakarta.ws.rs.WebApplicationException
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
    private val localAddress = clientSocket.localSocketAddress as InetSocketAddress
    private val currentRequest = AtomicReference<Mu3Request?>()
    private val completedRequests = AtomicLong()

    fun start(outputStream: OutputStream) {

        try {
            clientSocket.getInputStream().use { reqStream ->
                val requestParser = Http1MessageParser(HttpMessageType.REQUEST, requestPipeline, reqStream)
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
                    closeConnection = muRequest.headers().closeConnection(muRequest.httpVersion)

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
                            if (!handled) throw HttpException(HttpStatus.NOT_FOUND_404, "This page is not available. Sorry about that.")
                        } catch (e: Exception) {
                            if (muResponse.hasStartedSendingData()) {
                                // can't write a custom error at this point
                                throw e
                            } else {
                                server.exceptionHandler.handle(muRequest, muResponse, e)
                            }
                        }
                        if (!closeConnection) {
                            closeConnection = muResponse.headers().closeConnection(muRequest.httpVersion())
                        }
                        muRequest.cleanup()
                        muResponse.cleanup()
                    } catch (e: Exception) {
                        closeConnection = true
                        log.warn("Unrecoverable error for $muRequest", e)
                        muResponse.state = ResponseState.ERRORED
                    } finally {
                        onRequestEnded(muRequest, muResponse)
                    }

                }
            }
        } catch (e: Exception) {
            log.error("Unhandled error at the socket", e)
        } finally {
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

    override fun toString() = "HTTP1 connection from $remoteAddress to $localAddress"

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