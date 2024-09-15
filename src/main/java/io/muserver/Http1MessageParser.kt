package io.muserver

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.text.ParseException
import java.util.*

internal const val SP = 32.toByte()
internal const val CR = 13.toByte()
internal const val LF = 10.toByte()
private const val HTAB = 9.toByte()
private const val A = 65.toByte()
private const val A_LOWER = 97.toByte()
private const val F = 70.toByte()
private const val F_LOWER = 102.toByte()
private const val Z = 90.toByte()
internal const val COLON = 58.toByte()
private const val SEMICOLON = 59.toByte()
internal val COLON_SP = byteArrayOf(COLON, SP)
internal val CRLF = byteArrayOf(CR, LF)
private const val ZERO = 48.toByte()
private const val NINE = 57.toByte()

internal interface HttpMessageListener {
    fun onHeaders(exchange: HttpMessageTemp)
    fun onBodyBytes(exchange: HttpMessageTemp, type: BodyBytesType, array: ByteArray, offset: Int, length: Int)
    fun onMessageEnded(exchange: HttpMessageTemp)
    fun onError(exchange: HttpMessageTemp, error: Exception)
}

/**
 * The class of bytes in a message body
 */
enum class BodyBytesType {
    /**
     * Bytes that make up the content of an HTTP request or response.
     */
    CONTENT,

    /**
     * The raw bytes of the trailers of a message.
     */
    TRAILERS,

    /**
     * Bytes of a websocket frame. Not this may be a partial frame, or a single frame, or multiple frames.
     */
    WEBSOCKET_FRAME,
}

internal class Http1MessageParser(type: HttpMessageType, private val requestQueue: Queue<HttpRequestTemp>, private val source: InputStream) {

    /**
     * The number of bytes remaining to be sent in a fixed body size, or in the current chunk of chunked data (or MAX_LENGTH for unspecified lengths)
     */
    private var remainingBytesToProxy: Long = 0L
    private var state : ParseState
    private val buffer = ByteArrayOutputStream()
    private var exchange : HttpMessageTemp
    private var headerName : String? = null
    init {
        if (type == HttpMessageType.REQUEST) {
            exchange = HttpRequestTemp.empty()
            state = ParseState.REQUEST_START
        } else {
            exchange = HttpResponseTemp.empty()
            state = ParseState.RESPONSE_START
        }
    }

    private val log : Logger = LoggerFactory.getLogger(Http1MessageParser::class.java)

    fun feed(bytes: ByteArray, offset: Int, length: Int, listener: HttpMessageListener) {
        throw NotImplementedError()
    }
    
    private var bytes = ByteArray(8192)
    private var position = 0
    private var limit = 0
    private fun remaining() = limit - position
    private fun hasRemaining() = remaining() > 0
    fun readNext() : Http1ConnectionMsg {
        if (limit == -1) return EOFMsg
        while (true) {
            if (!hasRemaining()) {
                position = 0
                limit = source.read(bytes)
                if (limit == -1) {
                    if (state == ParseState.UNSPECIFIED_BODY) {
                        return EndOfBodyBit
                    }
                    return EOFMsg
                }
            }
            while (hasRemaining()) {
                val b = bytes[position]
                when (state) {
                    ParseState.REQUEST_START -> {
                        if (b.isUpperCase()) {
                            requestQueue.offer(exchange as HttpRequestTemp)
                            state = ParseState.METHOD
                            buffer.append(b)
                        } else throw ParseException("state=$state b=$b", position)
                    }

                    ParseState.METHOD -> {
                        if (b.isUpperCase()) {
                            buffer.append(b)
                        } else if (b == SP) {
                            request().method = try {
                                Method.valueOf(buffer.consumeAscii())
                            } catch (e: IllegalArgumentException) {
                                throw HttpException(HttpStatusCode.METHOD_NOT_ALLOWED_405)
                            }
                            state = ParseState.REQUEST_TARGET
                        } else throw ParseException("state=$state b=$b", position)
                    }

                    ParseState.REQUEST_TARGET -> {
                        if (b.isVChar()) { // todo: only allow valid target chars
                            buffer.append(b)
                        } else if (b == SP) {
                            request().url = buffer.consumeAscii()
                            state = ParseState.HTTP_VERSION
                        } else throw ParseException("state=$state b=$b", position)
                    }

                    ParseState.HTTP_VERSION -> {
                        if (b.isCR()) {
                            state = ParseState.REQUEST_LINE_ENDING
                        } else {
                            if (b.isVChar()) {
                                buffer.append(b)
                            } else throw ParseException("state=$state b=$b", position)
                        }
                    }

                    ParseState.REQUEST_LINE_ENDING -> {
                        if (b.isLF()) {
                            exchange.httpVersion = buffer.consumeHttpVersion()
                            state = ParseState.HEADER_START
                        } else throw ParseException("state=$state b=$b", position)
                    }

                    ParseState.RESPONSE_START -> {
                        if (b == SP) {
                            exchange.httpVersion = buffer.consumeHttpVersion()
                            state = ParseState.STATUS_CODE
                        } else {
                            if (b.isVChar()) {
                                buffer.append(b)
                            } else throw ParseException("state=$state b=$b", position)
                        }
                    }

                    ParseState.STATUS_CODE -> {
                        if (b.isDigit()) {
                            buffer.append(b)
                            if (buffer.size() > 3) throw ParseException("status code too long", position)
                        } else if (b == SP) {
                            val code = buffer.consumeAscii().toInt()
                            response().statusCode = code
                            if (code >= 200 || code == 101) {
                                response().request =
                                    requestQueue.poll() ?: throw ParseException("Got a response without a request", position)
                            }
                            state = ParseState.REASON_PHRASE
                        } else throw ParseException("state=$state b=$b", position)
                    }

                    ParseState.REASON_PHRASE -> {
                        if (b.isVChar() || b.isOWS()) {
                            buffer.append(b)
                        } else if (b == CR) {
                            state = ParseState.STATUS_LINE_ENDING
                        } else throw ParseException("state=$state b=$b", position)
                    }

                    ParseState.STATUS_LINE_ENDING -> {
                        if (b.isLF()) {
                            response().reason = buffer.consumeAscii()
                            state = ParseState.HEADER_START
                        } else throw ParseException("state=$state b=$b", position)
                    }

                    ParseState.HEADER_START -> {
                        if (b.isTChar()) {
                            buffer.append(b.toLower())
                            state = ParseState.HEADER_NAME
                        } else if (b.isCR()) {
                            state = ParseState.HEADERS_ENDING
                        } else throw ParseException("state=$state b=$b", position)
                    }

                    ParseState.HEADER_NAME -> {
                        if (b.isTChar()) {
                            buffer.append(b.toLower())
                        } else if (b == 58.toByte()) {
                            headerName = buffer.consumeAscii()
                            if (headerName!!.isEmpty()) throw ParseException("Empty header name", position)
                            state = ParseState.HEADER_NAME_ENDED
                        }
                    }

                    ParseState.HEADER_NAME_ENDED -> {
                        if (b.isOWS()) {
                            // skip it
                        } else if (b.isVChar()) {
                            buffer.append(b)
                            state = ParseState.HEADER_VALUE
                        } else throw ParseException("Invalid header value $b", position)
                    }

                    ParseState.HEADER_VALUE -> {
                        if (b.isVChar() || b.isOWS()) {
                            buffer.append(b)
                        } else if (b.isCR()) {
                            state = ParseState.HEADER_VALUE_ENDING
                        }
                    }

                    ParseState.HEADER_VALUE_ENDING -> {
                        if (b.isLF()) {
                            val value = buffer.consumeAscii().trimEnd()
                            if (value.isEmpty()) throw ParseException("No header value for header $headerName", position)
                            exchange.headers().add(headerName!!, value)
                            state = ParseState.HEADER_START
                        } else throw ParseException("No LF after CR at $state", position)
                    }

                    ParseState.HEADERS_ENDING -> {
                        if (b.isLF()) {
                            val exc = exchange
                            val body = exc.bodyTransferSize()
                            when (body.type) {
                                BodyType.FIXED_SIZE -> {
                                    val len = body.bytes!!
                                    state = ParseState.FIXED_SIZE_BODY
                                    remainingBytesToProxy = len
                                }

                                BodyType.CHUNKED -> {
                                    state = ParseState.CHUNK_START
                                }

                                BodyType.UNSPECIFIED -> {
                                    state = ParseState.UNSPECIFIED_BODY
                                    remainingBytesToProxy = Long.MAX_VALUE
                                }

                                BodyType.NONE -> {
                                    onMessageEnded()
                                }
                            }

                            position++
                            return exc
                        } else throw ParseException("state=$state b=$b", position)
                    }

                    ParseState.FIXED_SIZE_BODY, ParseState.UNSPECIFIED_BODY -> {
                        position++
                        return sendContent()
                    }

                    ParseState.CHUNK_START -> {
                        if (b.isHexDigit()) {
                            state = ParseState.CHUNK_SIZE
                            buffer.append(b)
                        } else throw ParseException("state=$state b=$b", position)
                    }

                    ParseState.CHUNK_SIZE -> {
                        if (b.isHexDigit()) {
                            buffer.append(b)
                        } else {
                            state = if (b == SEMICOLON) {
                                ParseState.CHUNK_EXTENSIONS
                            } else if (b.isCR()) {
                                ParseState.CHUNK_HEADER_ENDING
                            } else throw ParseException("state=$state b=$b", position)
                            remainingBytesToProxy = buffer.consumeAscii().toLong(16)
                        }
                    }

                    ParseState.CHUNK_EXTENSIONS -> {
                        if (b.isVChar() || b.isOWS()) {
                            // todo: only allow valid extension characters
                        } else if (b.isCR()) {
                            state = ParseState.CHUNK_HEADER_ENDING
                        } else throw ParseException("state=$state b=$b", position)
                    }

                    ParseState.CHUNK_HEADER_ENDING -> {
                        if (b.isLF()) {
                            // remainingBytesToProxy has the chunk size in it
                            state = if (remainingBytesToProxy == 0L) ParseState.LAST_CHUNK else ParseState.CHUNK_DATA
                        } else throw ParseException("state=$state b=$b", position)
                    }

                    ParseState.CHUNK_DATA -> {
                        return sendContent()
                    }

                    ParseState.CHUNK_DATA_READ -> {
                        if (b.isCR()) {
                            state = ParseState.CHUNK_DATA_ENDING
                        } else throw ParseException("state=$state b=$b", position)
                    }

                    ParseState.CHUNK_DATA_ENDING -> {
                        if (b.isLF()) {
                            state = ParseState.CHUNK_START
                        } else throw ParseException("state=$state b=$b", position)
                    }

                    ParseState.LAST_CHUNK -> {
                        if (b.isCR()) {
                            state = ParseState.CHUNKED_BODY_ENDING
                        } else if (b.isTChar()) {
                            buffer.append(b)
                            state = ParseState.TRAILERS
                        } else throw ParseException("state=$state b=$b", position)
                    }

                    ParseState.CHUNKED_BODY_ENDING -> {
                        if (b.isLF()) {
                            position++
                            onMessageEnded()
                            return EndOfBodyBit
                        } else throw ParseException("state=$state b=$b", position)
                    }

                    ParseState.TRAILERS -> {
                        if (b.isOWS() || b.isVChar() || b.isCR()) {
                            buffer.append(b)
                        } else if (b.isLF()) {
                            buffer.append(b)
                            val trailerPart = buffer.toString(StandardCharsets.US_ASCII)
                            if (trailerPart.endsWith("\r\n\r\n")) {
                                buffer.reset()
                                onMessageEnded()
                                throw NotImplementedError("Trailers not yet supported")
                            }
                        } else throw ParseException("state=$state b=$b", position)
                    }

                    ParseState.WEBSOCKET -> {
                        throw NotImplementedError("No websockets yet")
                    }
                }
                position++
            }
        }
    }

    fun eof(listener: HttpMessageListener) {
        if (state.eofAction == EOFAction.COMPLETE) {
            listener.onMessageEnded(exchange)
        } else if (state.eofAction == EOFAction.ERROR) {
            listener.onError(exchange, EOFException("EOF when state is $state"))
        }
    }

    private fun request() = (exchange as HttpRequestTemp)
    private fun response() = (exchange as HttpResponseTemp)

    private fun sendContent(): MessageBodyBit {
        val remainingInBuffer = remaining()
        val numberToTransfer = minOf(remainingBytesToProxy, remainingInBuffer.toLong()).toInt()
        remainingBytesToProxy -= numberToTransfer
        val start = position
        position += numberToTransfer

        val isLast = if (state == ParseState.CHUNK_DATA) {
            if (remainingBytesToProxy == 0L) {
                state = ParseState.CHUNK_DATA_READ
            }
            false
        } else {
            remainingBytesToProxy == 0L
        }
        if (isLast) {
            onMessageEnded()
        }
        return MessageBodyBit(bytes, start, numberToTransfer, isLast)
    }

    private fun onMessageEnded() {
        val exc = exchange
        this.state = if (exc is HttpRequestTemp) {
            if (exc.isWebsocketUpgrade()) {
                ParseState.WEBSOCKET
            } else {
                this.exchange = HttpRequestTemp.empty()
                ParseState.REQUEST_START
            }
        } else {
            if (exc.headers().containsValue("upgrade", "websocket", false)) {
                ParseState.WEBSOCKET
            } else {
                this.exchange = HttpResponseTemp.empty()
                ParseState.RESPONSE_START
            }
        }
    }

    fun error(e: Exception, messageListener: HttpMessageListener) {
        messageListener.onError(exchange, e)
    }

    private enum class ParseState(val eofAction: EOFAction) {
        REQUEST_START(EOFAction.NOTHING),
        RESPONSE_START(EOFAction.NOTHING),
        METHOD(EOFAction.ERROR),
        REQUEST_TARGET(EOFAction.ERROR),
        HTTP_VERSION(EOFAction.ERROR),
        REQUEST_LINE_ENDING(EOFAction.ERROR),
        STATUS_CODE(EOFAction.ERROR),
        REASON_PHRASE(EOFAction.ERROR),
        STATUS_LINE_ENDING(EOFAction.ERROR),
        HEADER_START(EOFAction.ERROR),
        HEADER_NAME(EOFAction.ERROR),
        HEADER_NAME_ENDED(EOFAction.ERROR),
        HEADER_VALUE(EOFAction.ERROR),
        HEADER_VALUE_ENDING(EOFAction.ERROR),
        HEADERS_ENDING(EOFAction.ERROR),
        FIXED_SIZE_BODY(EOFAction.ERROR),
        UNSPECIFIED_BODY(EOFAction.COMPLETE),
        CHUNK_START(EOFAction.ERROR),
        CHUNK_SIZE(EOFAction.ERROR),
        CHUNK_EXTENSIONS(EOFAction.ERROR),
        CHUNK_HEADER_ENDING(EOFAction.ERROR),
        CHUNK_DATA(EOFAction.ERROR),
        LAST_CHUNK(EOFAction.ERROR),
        CHUNKED_BODY_ENDING(EOFAction.ERROR),
        TRAILERS(EOFAction.ERROR),
        WEBSOCKET(EOFAction.COMPLETE),
        CHUNK_DATA_READ(EOFAction.ERROR),
        CHUNK_DATA_ENDING(EOFAction.ERROR),
    }

    private enum class EOFAction { NOTHING, ERROR, COMPLETE }

    override fun toString(): String {
        return javaClass.simpleName + " ${this.state}"
    }

    companion object {
        private fun Byte.isVChar() = this >= 0x21.toByte() && this <= 0x7E.toByte()
        internal fun Byte.isTChar(): Boolean {
            // tchar = '!' / '#' / '$' / '%' / '&' / ''' / '*' / '+' / '-' / '.' /
            //    '^' / '_' / '`' / '|' / '~' / DIGIT / ALPHA
            return this == 33.toByte()
                    || (this in 35.toByte()..39.toByte())
                    || this == 42.toByte() || this == 43.toByte() || this == 45.toByte() || this == 46.toByte()
                    || (this in ZERO..NINE) // 0-9
                    || (this in A..Z) // A-Z
                    || (this in 94.toByte()..122.toByte()) // ^, ), `, a-z
                    || this == 124.toByte() || this == 126.toByte()
        }

        private fun Byte.isUpperCase() = this in A..Z
        private fun Byte.isCR() = this == CR
        private fun Byte.isLF() = this == LF
        private fun Byte.isOWS() = this == SP || this == HTAB
        private fun Byte.toLower(): Byte = if (this < A || this > Z) this else (this + 32).toByte()
        private fun Byte.isDigit() = this in ZERO..NINE
        private fun Byte.isHexDigit() = this in A..F || this in ZERO..NINE || this in A_LOWER..F_LOWER
        private fun ByteArrayOutputStream.append(b: Byte) {
            this.write(b.toInt())
            if (this.size() > 16384) throw IllegalStateException("Buffer is ${this.size()} bytes")
        }

        private fun ByteArrayOutputStream.consumeAscii(): String {
            val v = this.toString(StandardCharsets.US_ASCII)
            this.reset()
            return v
        }

        private fun ByteArrayOutputStream.consumeHttpVersion(): HttpVersion {
            return HttpVersion.fromVersion(this.consumeAscii())
                ?: throw HttpException(HttpStatusCode.HTTP_VERSION_NOT_SUPPORTED_505)
        }
    }
}

internal enum class HttpMessageType { REQUEST, RESPONSE }

internal interface Http1ConnectionMsg
class MessageBodyBit(val bytes: ByteArray, val offset: Int, val length: Int, val isLast: Boolean) : Http1ConnectionMsg
object EmptyMessageBodyBit : Http1ConnectionMsg
object EndOfBodyBit : Http1ConnectionMsg
object EOFMsg : Http1ConnectionMsg
