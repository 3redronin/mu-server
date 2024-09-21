package io.muserver

import java.io.OutputStream
import java.io.PrintWriter
import java.net.URI
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

internal class Mu3Response(
    private val muRequest: Mu3Request,
    private val socketOut: OutputStream,
) : MuResponse, ResponseInfo {

    private var status : HttpStatus = HttpStatus.OK_200
    private val headers = Mu3Headers()
    var state : ResponseState = ResponseState.NOTHING
    @Volatile
    private var wrappedOut: OutputStream? = null
    @Volatile
    private var writer: PrintWriter? = null
    private var endMillis : Long? = null
    private var completionListeners: ConcurrentLinkedQueue<ResponseCompleteListener>? = null

    override fun status() = status.code()
    override fun statusValue() = status

    override fun status(value: HttpStatus) {
        this.status = value
    }
    override fun status(value: Int) {
        this.status = HttpStatus.of(value)
    }
    override fun addCompletionListener(listener: ResponseCompleteListener?) {
        if (listener == null) {
            throw NullPointerException("Null completion listener")
        }
        if (completionListeners == null) {
            completionListeners = ConcurrentLinkedQueue()
        }
        completionListeners!!.add(listener)
    }
    fun completionListeners(): Iterable<ResponseCompleteListener> {
        return completionListeners ?: emptyList()
    }

    private fun writeStatusAndHeaders() {
        if (state != ResponseState.NOTHING) {
            throw IllegalStateException("Cannot write headers multiple times")
        }
        state = ResponseState.WRITING_HEADERS
        socketOut.write(status.http11ResponseLine())
        if (!headers.contains(HeaderNames.DATE)) {
            headers.set("date", Mutils.toHttpDate(Date()))
        }
        headers.writeTo(socketOut)
        socketOut.write(CRLF, 0, 2)
    }

    override fun sendInformationalResponse(status: HttpStatus, headers: Headers?) {
        if (!status.isInformational) throw  IllegalArgumentException("Only informational status is allowed but received $status")
        if (state != ResponseState.NOTHING) throw IllegalStateException("Informational headers cannot be sent after the main response headers have been sent")
        socketOut.write(status.http11ResponseLine())
        if (headers != null) {
            (headers as Mu3Headers).writeTo(socketOut)
        }
        socketOut.write(CRLF, 0, 2)
        socketOut.flush()
    }


    override fun write(text: String) {
        val charset = ensureCharsetSet()
        val bytes = text.toByteArray(charset)
        headers.set("content-length", bytes.size)
        outputStream().use { it.write(bytes) }
        state = ResponseState.FULL_SENT
    }

    private fun ensureCharsetSet(): Charset {
        val charset = NettyRequestAdapter.bodyCharset(headers, false)
        if (!headers.contains(HeaderNames.CONTENT_TYPE)) {
            headers[HeaderNames.CONTENT_TYPE] =
                if (charset === StandardCharsets.UTF_8) ContentTypes.TEXT_PLAIN_UTF8 else "text/plain;charset=" + charset.name()
        }
        return charset
    }


    override fun sendChunk(text: String) {
        val charset = ensureCharsetSet()
        val out = outputStream()
        out.write(text.toByteArray(charset))
        out.flush()
    }

    override fun redirect(url: String) {
        redirect(URI.create(url))
    }

    override fun redirect(uri: URI) {
        if (!status.isRedirection) {
            status(HttpStatus.FOUND_302)
        }
        val ex = HttpException(status, null as String?)
        ex.responseHeaders().set(HeaderNames.LOCATION, uri.normalize().toString())
        throw ex
    }

    override fun headers() = headers

    override fun contentType(contentType: CharSequence?) {
        if (contentType == null) {
            headers.remove(HeaderNames.CONTENT_TYPE)
        } else {
            headers.set(HeaderNames.CONTENT_TYPE, contentType)
        }
    }

    override fun addCookie(cookie: Cookie) {
        headers.add(HeaderNames.SET_COOKIE, cookie.toString())
    }

    override fun outputStream() = outputStream(8192)

    override fun outputStream(bufferSize: Int): OutputStream {

        if (wrappedOut == null) {
            var responseEncoder : ContentEncoder? = null
            for (contentEncoder in request().server().contentEncoders()) {
                val theOne = contentEncoder.prepare(muRequest, this)
                if (theOne) {
                    responseEncoder = contentEncoder
                    break
                }
            }

            val fixedLen = headers.getLong(HeaderNames.CONTENT_LENGTH.toString(), -1)
            if (fixedLen == -1L) {
                headers.set(HeaderNames.TRANSFER_ENCODING, HeaderValues.CHUNKED)
                wrappedOut = ChunkedOutputStream(socketOut)
            } else {
                wrappedOut = FixedSizeOutputStream(fixedLen, socketOut)
            }

            writeStatusAndHeaders()
            if (responseEncoder != null) {
                wrappedOut = responseEncoder.wrapStream(muRequest, this, wrappedOut)
            }
        }
        return wrappedOut!!
    }

    override fun writer(): PrintWriter {
        if (writer == null) {
            writer = PrintWriter(outputStream(), false, ensureCharsetSet())
        }
        return writer!!
    }

    override fun hasStartedSendingData() = state != ResponseState.NOTHING

    override fun responseState() = state

    fun cleanup() {
        if (state == ResponseState.NOTHING) {
            // empty response body
            if (status == HttpStatus.OK_200) {
                status(HttpStatus.NO_CONTENT_204)
            }
            if (status.canHaveContent()) {
                headers.set(HeaderNames.CONTENT_LENGTH, 0L)
            }
            writeStatusAndHeaders()
            socketOut.flush()
        } else {
            writer?.close()
            wrappedOut?.close()
        }
        if (!state.endState()) {
            state = ResponseState.FINISHED
        }
    }

    override fun duration() = (endMillis ?: System.currentTimeMillis()) - muRequest.startTime()
    override fun completedSuccessfully() = state.completedSuccessfully()
    override fun request() = muRequest
    override fun response() = this

    override fun toString(): String {
        return "$status ($state)"
    }
}
