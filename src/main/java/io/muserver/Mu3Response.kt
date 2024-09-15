package io.muserver

import java.io.OutputStream
import java.io.PrintWriter
import java.net.URI
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.*

internal class Mu3Response(muRequest: Mu3Request, val socketOut: OutputStream) : MuResponse {

    private var status : HttpStatusCode = HttpStatusCode.OK_200
    private val headers = Mu3Headers()
    var state : ResponseState = ResponseState.NOTHING
    private var wrappedOut : OutputStream? = null

    override fun status() = status.code()

    override fun status(value: HttpStatusCode) {
        this.status = value
    }
    override fun status(value: Int) {
        this.status = HttpStatusCode.of(value)
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
            status(HttpStatusCode.FOUND_302)
        }
        val ex = HttpException(status)
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
            val fixedLen = headers.getLong(HeaderNames.CONTENT_LENGTH.toString(), -1)
            if (fixedLen == -1L) {
                headers.set(HeaderNames.TRANSFER_ENCODING, HeaderValues.CHUNKED)
                wrappedOut = ChunkedOutputStream(socketOut)
            } else {
                wrappedOut = FixedSizeOutputStream(fixedLen, socketOut)
            }
            writeStatusAndHeaders()
        }
        return wrappedOut!!
    }

    override fun writer(): PrintWriter {
        return PrintWriter(outputStream(), false, ensureCharsetSet())
    }

    override fun hasStartedSendingData() = state != ResponseState.NOTHING

    override fun responseState() = state

    fun cleanup() {
        if (state == ResponseState.NOTHING) {
            // empty response body
            if (status == HttpStatusCode.OK_200) {
                status(HttpStatusCode.NO_CONTENT_204)
            }
            headers.set(HeaderNames.CONTENT_LENGTH, 0L)
            writeStatusAndHeaders()
            socketOut.flush()
        } else {
            wrappedOut?.close()
        }
    }

}
