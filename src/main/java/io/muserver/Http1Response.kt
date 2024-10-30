package io.muserver

import java.io.OutputStream
import java.util.*

internal class Http1Response(
    private val muRequest: Mu3Request,
    private val socketOut: OutputStream,
) : BaseResponse(Mu3Headers()), MuResponse, ResponseInfo {

    var websocket: WebsocketConnection? = null
        private set

    @Volatile
    private var wrappedOut: OutputStream? = null

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
            var responseEncoder : ContentEncoder? = null
            for (contentEncoder in request().server().contentEncoders()) {
                val theOne = contentEncoder.prepare(muRequest, this)
                if (theOne) {
                    responseEncoder = contentEncoder
                    break
                }
            }

            val fixedLen = headers().getLong(HeaderNames.CONTENT_LENGTH.toString(), -1)
            val rawOut = if (muRequest.method.isHead) DiscardingOutputStream.INSTANCE else socketOut
            if (fixedLen == -1L) {
                headers().set(HeaderNames.TRANSFER_ENCODING, HeaderValues.CHUNKED)
                wrappedOut = ChunkedOutputStream(rawOut)
            } else {
                wrappedOut = FixedSizeOutputStream(fixedLen, rawOut)
            }

            writeStatusAndHeaders()
            if (responseEncoder != null) {
                wrappedOut = responseEncoder.wrapStream(muRequest, this, wrappedOut)
            }
        }
        return wrappedOut!!
    }




    public override fun cleanup() {
        if (responseState() == ResponseState.NOTHING) {
            // empty response body
            if (!muRequest.method.isHead && status().canHaveContent() && !headers().contains(HeaderNames.CONTENT_LENGTH)) {
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

    public override fun setState(newState: ResponseState) {
        super.setState(newState)
        if  (newState.endState()) {
            endMillis = System.currentTimeMillis()
        }
    }

    override fun duration() = (endMillis ?: System.currentTimeMillis()) - muRequest.startTime()
    override fun completedSuccessfully() = responseState().completedSuccessfully() && muRequest.completedSuccessfully()
    override fun request() = muRequest
    override fun response() = this

    override fun toString(): String {
        return "${status()} (${responseState()})"
    }

    fun upgrade(websocket: WebsocketConnection) {
        this.websocket = websocket
    }
}
