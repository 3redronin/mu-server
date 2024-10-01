package io.muserver

import io.muserver.Mu3Headers.Companion.headerBytes
import java.io.OutputStream

internal sealed interface HttpMessageTemp : Http1ConnectionMsg {
    var httpVersion: HttpVersion?
    fun headers() : Mu3Headers
    var bodySize: BodySize?
    fun bodyTransferSize() : BodySize
    companion object {
        internal fun fixedBodyLength(cl: List<String>): BodySize {
            if (cl.size > 1) {
                // 6.3.5 but ignoring the fact there may be multiple with all the same value
                throw IllegalStateException("Multiple content-length headers")
            }
            // 6.3.5
            val len = try {
                cl[0].toLong()
            } catch (e: NumberFormatException) {
                throw IllegalStateException("Invalid content-length")
            }
            if (len < 0L) throw IllegalStateException("Negative content length $len")
            // 6.3.6
            return if (len == 0L) BodySize.NONE else BodySize(BodyType.FIXED_SIZE, len)
        }
    }

}


internal data class HttpRequestTemp(
    var method: Method?,
    var url: String,
    override var httpVersion: HttpVersion?,
    override var bodySize: BodySize?,
    private val headers: Mu3Headers = Mu3Headers(),
    var rejectRequest: HttpException? = null,
) : HttpMessageTemp {

    fun normalisedUri(): String {
        return Mutils.getRelativeUrl(url)
    }

    fun isWebsocketUpgrade() = headers.containsValue("upgrade", "websocket", false)

    override fun bodyTransferSize() : BodySize {
        // numbers referring to sections in https://httpwg.org/specs/rfc9112.html#message.body.length
        val cl = headers.getAll("content-length")
        val isChunked = headers.hasChunkedBody()
        // 6.3.3
        if (isChunked && cl.isNotEmpty()) throw IllegalStateException("A request has chunked encoding and content-length $cl")
        // 6.3.4, except this assumes only a single transfer-encoding value
        if (isChunked) return BodySize.CHUNKED

        return if (cl.isEmpty()) {
            // 6.3.5
            BodySize.NONE
        } else {
            HttpMessageTemp.fixedBodyLength(cl)
        }
    }

    internal fun writeTo(out: OutputStream) {
        out.write(method!!.headerBytes())
        out.write(' '.code)
        out.write(url.headerBytes())
        out.write(' '.code)
        out.write(httpVersion!!.headerBytes())
        out.write(CRLF)
        headers.writeTo(out)
        out.write(CRLF)
    }

    companion object {
        internal fun empty() = HttpRequestTemp(null, "", null, null)
    }

    override fun headers() = headers

}

internal data class HttpResponseTemp(
    var request: HttpRequestTemp?,
    override var httpVersion: HttpVersion?,
    var statusCode: Int,
    var reason: String,
    override var bodySize: BodySize?,
    private val headers: Mu3Headers = Mu3Headers(),
) : HttpMessageTemp {

    fun isInformational() = statusCode / 100 == 1

    override fun bodyTransferSize() : BodySize {
        // numbers referring to sections in https://httpwg.org/specs/rfc9112.html#message.body.length

        // 6.3.1
        if (isInformational() || statusCode == 204 || statusCode == 304) return BodySize.NONE
        val req = request ?: throw IllegalStateException("Cannot tell the size without the request")
        if (req.method!!.isHead) return BodySize.NONE

        // 6.3.2
        if (req.method == Method.CONNECT) return BodySize.UNSPECIFIED

        // 6.3.3
        val cl = headers.getAll("content-length")
        val isChunked = headers.hasChunkedBody()
        if (isChunked && cl.isNotEmpty()) throw IllegalStateException("A response has chunked encoding and content-length $cl")
        // 6.3.4, except this assumes only a single transfer-encoding value
        if (isChunked) return BodySize.CHUNKED
        return if (cl.isEmpty()) {
            // 6.3.8
            BodySize.UNSPECIFIED
        } else {
            HttpMessageTemp.fixedBodyLength(cl)
        }
    }

    internal fun writeTo(out: OutputStream) {
        out.write(httpVersion!!.headerBytes())
        out.write(' '.code)
        out.write(statusCode.toString().headerBytes())
        out.write(' '.code)
        out.write(reason.headerBytes())
        out.write(CRLF)
        headers.writeTo(out)
        out.write(CRLF)
    }
    override fun headers() = headers

    companion object {
        internal fun empty() = HttpResponseTemp(null, null, 0, "", null)
    }

}