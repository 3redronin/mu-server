package io.muserver

import java.io.InputStream
import java.net.URI
import java.util.*

internal class Mu3Request(
    val method: Method,
    val requestUri: URI,
    val serverUri: URI,
    val httpVersion: HttpVersion,
    val mu3Headers: Mu3Headers,
    val bodySize: BodySize,
) : MuRequest {
    override fun contentType() = mu3Headers.get("content-type")
    private val startTime = System.currentTimeMillis()
    private var query: QueryString? = null
    private var attributes: MutableMap<String, Any>? = null

    override fun startTime() = startTime

    override fun method() = method

    override fun uri() = requestUri

    override fun serverURI() = serverUri

    override fun headers() = mu3Headers

    override fun inputStream(): Optional<InputStream> {
        TODO("Not yet implemented")
    }

    override fun readBodyAsString(): String {
        TODO("Not yet implemented")
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
        TODO("Not yet implemented")
    }

    override fun cookie(name: String?): Optional<String> {
        TODO("Not yet implemented")
    }

    override fun contextPath(): String {
        TODO("Not yet implemented")
    }

    override fun relativePath() = requestUri.rawPath!!

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

    override fun remoteAddress(): String {
        TODO("Not yet implemented")
    }

    override fun clientIP(): String {
        TODO("Not yet implemented")
    }

    override fun server(): MuServer {
        TODO("Not yet implemented")
    }

    override fun isAsync(): Boolean {
        TODO("Not yet implemented")
    }

    override fun protocol(): String {
        TODO("Not yet implemented")
    }

    override fun connection(): HttpConnection {
        TODO("Not yet implemented")
    }

    override fun toString(): String {
        return httpVersion.version() + " " + method + " " + serverUri
    }

}