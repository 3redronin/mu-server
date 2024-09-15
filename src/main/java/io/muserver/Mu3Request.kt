package io.muserver

import java.io.InputStream
import java.net.URI
import java.util.*

internal class Mu3Request(
    val connection: HttpConnection,
    val method: Method,
    val requestUri: URI,
    val serverUri: URI,
    val httpVersion: HttpVersion,
    val mu3Headers: Mu3Headers,
    val bodySize: BodySize,
    val body: InputStream,
) : MuRequest {
    override fun contentType() = mu3Headers.get("content-type")
    private val startTime = System.currentTimeMillis()
    private var query: QueryString? = null
    private var attributes: MutableMap<String, Any>? = null
    private var contextPath = ""
    private var relativePath: String = requestUri.rawPath
    private var cookies: List<Cookie>? = null


    override fun startTime() = startTime

    override fun method() = method

    override fun uri() = requestUri

    override fun serverURI() = serverUri

    override fun headers() = mu3Headers

    override fun inputStream(): Optional<InputStream> {
        return if (bodySize == BodySize.NONE) Optional.empty() else Optional.of(body())
    }

    override fun body() = body

    override fun readBodyAsString(): String {
        val charset = NettyRequestAdapter.bodyCharset(mu3Headers, true)
        body().reader(charset).use { reader ->
            return reader.readText()
        }
    }

    override fun uploadedFiles(name: String?): MutableList<UploadedFile> {
        TODO("Not yet implemented")
    }

    override fun uploadedFile(name: String?): UploadedFile {
        TODO("Not yet implemented")
    }

    override fun query(): RequestParameters {
        if (this.query == null) {
            this.query = QueryString.parse(serverUri.rawQuery)
        }
        return query!!
    }

    override fun form(): RequestParameters {
        TODO("Not yet implemented")
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

    override fun attribute(key: String): Any? = attributes()[key]

    override fun attribute(key: String, value: Any) {
        attributes()[key] = value
    }

    override fun attributes(): MutableMap<String, Any> {
        if (attributes == null) {
            attributes = HashMap()
        }
        return attributes!!
    }

    override fun handleAsync(): AsyncHandle {
        TODO("Not yet implemented")
    }

    override fun server() = connection.server()!!

    override fun isAsync(): Boolean = false

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

}