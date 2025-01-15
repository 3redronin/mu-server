package io.muserver

import io.muserver.ParseUtils.CRLF
import java.io.OutputStream
import java.util.*

internal class Http1Response(
    muRequest: Mu3Request,
    private val socketOut: OutputStream,
) : BaseResponse(muRequest, Mu3Headers()), MuResponse, ResponseInfo {

    var websocket: WebsocketConnection? = null
        private set


    private var endMillis : Long? = null



    private fun writeStatusAndHeaders() {
        if (responseState() != ResponseState.NOTHING) {
            throw IllegalStateException("Cannot write headers multiple times")
        }
        setState(ResponseState.WRITING_HEADERS)

        socketOut.write(status().http11ResponseLine())
        if (!headers().contains(HeaderNames.DATE)) {
            headers().set("date", Mutils.toHttpDate(Date()))
        }
        (headers() as Mu3Headers).writeTo(socketOut)
        socketOut.write(CRLF, 0, 2)
    }

    override fun sendInformationalResponse(status: HttpStatus, headers: Headers?) {
        if (!status.isInformational) throw  IllegalArgumentException("Only informational status is allowed but received $status")
        if (responseState() != ResponseState.NOTHING) throw IllegalStateException("Informational headers cannot be sent after the main response headers have been sent")
        socketOut.write(status.http11ResponseLine())
        if (headers != null) {
            (headers as Mu3Headers).writeTo(socketOut)
        }
        socketOut.write(CRLF, 0, 2)
        socketOut.flush()
    }


    override fun outputStream(bufferSize: Int): OutputStream {
        if (wrappedOut == null) {
            var responseEncoder: ContentEncoder? = contentEncoder()

            val fixedLen = headers().getLong(HeaderNames.CONTENT_LENGTH.toString(), -1)
            val rawOut = if (request.method.isHead) DiscardingOutputStream.INSTANCE else socketOut
            if (fixedLen == -1L) {
                headers().set(HeaderNames.TRANSFER_ENCODING, HeaderValues.CHUNKED)
                wrappedOut = ChunkedOutputStream(rawOut)
            } else {
                wrappedOut = FixedSizeOutputStream(fixedLen, rawOut)
            }

            writeStatusAndHeaders()
            if (responseEncoder != null) {
                wrappedOut = responseEncoder.wrapStream(request, this, wrappedOut)
            }
        } else throw IllegalStateException("Cannot specify buffer size for response output stream when it has already been created")
        return wrappedOut!!
    }


    override internal fun cleanup() {
        if (responseState() == ResponseState.NOTHING) {
            // empty response body
            if (!request.method.isHead && status().canHaveContent() && !headers().contains(HeaderNames.CONTENT_LENGTH)) {
                headers().set(HeaderNames.CONTENT_LENGTH, 0L)
            }
            writeStatusAndHeaders()
            socketOut.flush()
        } else {
            closeWriter()
            wrappedOut?.close()
        }
        if (!responseState().endState()) {
            setState(ResponseState.FINISHED)
        }
    }

    override internal fun setState(newState: ResponseState) {
        super.setState(newState)
        if  (newState.endState()) {
            endMillis = System.currentTimeMillis()
        }
    }

    override fun duration() = (endMillis ?: System.currentTimeMillis()) - request.startTime()
    override fun completedSuccessfully() = responseState().completedSuccessfully() && request.completedSuccessfully()
    override fun request() = request
    override fun response() = this

    override fun toString(): String {
        return "${status()} (${responseState()})"
    }

    fun upgrade(websocket: WebsocketConnection) {
        this.websocket = websocket
    }
}
