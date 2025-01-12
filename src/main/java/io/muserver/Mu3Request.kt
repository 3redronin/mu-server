package io.muserver

import jakarta.ws.rs.core.MediaType
import java.io.InputStream
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.*
import kotlin.math.min

internal class Mu3Request(
    val connection: HttpConnection,
    val method: Method,
    val requestUri: URI,
    val serverUri: URI,
    val httpVersion: HttpVersion,
    val mu3Headers: Headers,
    val bodySize: BodySize,
    val body: InputStream,
) : MuRequest {
    override fun contentType() = mu3Headers.get("content-type")
    private var form: MuForm? = null
    lateinit var response: BaseResponse
    private val startTime = System.currentTimeMillis()
    private var query: QueryString? = null
    private var attributes: MutableMap<String, Any>? = null
    private var contextPath = ""
    private var relativePath: String = requestUri.rawPath
    private var cookies: List<Cookie>? = null
    private var bodyClaimed = false

    private fun claimbody() {
        if (bodyClaimed) throw IllegalStateException("The body of the request message cannot be read twice. This can happen when calling any 2 of inputStream(), readBodyAsString(), or form() methods.")
        bodyClaimed = true
    }

    var asyncHandle : Mu3AsyncHandleImpl? = null
        private set

    override fun startTime() = startTime

    override fun method() = method

    override fun uri() = requestUri

    override fun serverURI() = serverUri

    override fun headers() = mu3Headers

    override fun inputStream(): Optional<InputStream> {
        return if (bodySize == BodySize.NONE) Optional.empty() else Optional.of(body())
    }

    override fun body() = body
    override fun declaredBodySize() = bodySize

    override fun readBodyAsString(): String {
        claimbody()
        val charset = Headtils.bodyCharset(mu3Headers, true)
        body().reader(charset).use { reader ->
            return reader.readText()
        }
    }

    override fun query(): RequestParameters {
        if (this.query == null) {
            this.query = QueryString.parse(serverUri.rawQuery)
        }
        return query!!
    }

    override fun form(): MuForm {
        if (this.form == null) {
            claimbody()
            val bodyType: MediaType? = mu3Headers.contentType()
            if (bodyType == null) {
                this.form = EmptyForm.VALUE
            } else {
                val type = bodyType.type.lowercase()
                val subtype = bodyType.subtype.lowercase()
                if ("application" == type && "x-www-form-urlencoded" == subtype) {
                    val text = body().reader(StandardCharsets.UTF_8).use { reader ->
                        reader.readText()
                    }
                    this.form = UrlEncodedMuForm.parse(text)
                } else if ("multipart" == type && "form-data" == subtype) {
                    val charset = Headtils.bodyCharset(mu3Headers, true)
                    val boundary = bodyType.parameters["boundary"]
                    if (boundary != null) {
                        val bufferSize = min(8192, bodySize.size() ?: 8192).toInt()
                        if (Mutils.nullOrEmpty(boundary)) throw HttpException.badRequest("No boundary specified in the multipart form-data")
                        val formParser = MultipartFormParser(server().tempDir(), boundary, body(), bufferSize, charset)
                        this.form = formParser.parseFully()
                    } else {
                        this.form = UrlEncodedMuForm(QueryString.EMPTY)
                    }
                } else {
                    throw HttpException.badRequest("Unrecognised form type $bodyType")
                }
            }

        }
        return this.form!!
    }

    override fun cookies(): List<Cookie> {
        if (this.cookies == null) {
            cookies = mu3Headers.cookies()
        }
        return this.cookies!!
    }

    override fun cookie(name: String): Optional<String> {
        return Optional.ofNullable(cookies().firstOrNull { it.name() == name }?.value())
    }

    override fun contextPath() = contextPath

    override fun relativePath() = relativePath

    override fun attribute(key: String): Any? = attributes?.get(key)

    override fun attribute(key: String, value: Any?) {
        if (value == null ) {
            attributes().remove(key)
        } else {
            attributes()[key] = value
        }
    }

    override fun attributes(): MutableMap<String, Any> {
        if (attributes == null) {
            attributes = HashMap()
        }
        return attributes!!
    }

    @Deprecated("see interface")
    override fun handleAsync(): AsyncHandle {
        if (asyncHandle == null) {
            asyncHandle = Mu3AsyncHandleImpl(this, response)
        }
        return asyncHandle!!
    }

    override fun isAsync(): Boolean = asyncHandle != null

    override fun httpVersion() = httpVersion

    override fun connection() = connection

    fun addContext(contextToAdd: String) {
        val ctx = normaliseContext(contextToAdd)
        this.contextPath += ctx
        this.relativePath = relativePath.substring(ctx.length)
    }

    fun setPaths(contextPath: String, relativePath: String) {
        this.contextPath = contextPath
        this.relativePath = relativePath
    }

    private fun normaliseContext(contextToAdd: String): String {
        var normal = contextToAdd
        if (normal.endsWith("/")) {
            normal = normal.substring(0, normal.length - 1)
        }
        if (!normal.startsWith("/")) {
            normal = "/$normal"
        }
        return normal
    }


    override fun toString(): String {
        return httpVersion.version() + " " + method + " " + serverUri
    }

    /**
     * Makes sure the body is fully read and cleans up resources (such as form data)
     *
     * @return `true` if the cleanup was successful; `false` if it wasn't (resulting in invalid request)
     */
    fun cleanup(): Boolean {
        try {
            if (body is Http1BodyStream) {
                val throwIfTooBig = response.status() != HttpStatus.CONTENT_TOO_LARGE_413
                // If we know it's too big, that means we want to tell the client so, in which case we need to
                // consume all the body, so don't throw when read amount is too large.
                // However if we didn't realise the request body is too large, and are doing something like returning
                // a 200, we want to close the connection to the client before reading all the data which causes
                // the client to detect an error.
                val bodyState = body.discardRemaining(throwIfTooBig)
                return bodyState == Http1BodyStream.State.EOF && !body.tooBig()
            } else {
                return true
            }
        } finally {
            if (form != null && form is MultipartForm) {
                (form as MultipartForm).cleanup()
            }
        }
    }

    fun completedSuccessfully(): Boolean {
        if (body is Http1BodyStream) {
            return body.state() == Http1BodyStream.State.EOF
        }
        return true
    }

}