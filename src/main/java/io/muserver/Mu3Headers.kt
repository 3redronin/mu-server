package io.muserver

import com.danielflower.ifp.BodySize
import com.danielflower.ifp.COLON_SP
import com.danielflower.ifp.CRLF
import com.danielflower.ifp.HttpHeaders
import com.danielflower.ifp.HttpHeaders.Companion.headerBytes
import jakarta.ws.rs.core.MediaType
import java.io.OutputStream

internal class Mu3Headers(private val raw: HttpHeaders = HttpHeaders()) : Headers {
    override fun iterator(): MutableIterator<MutableMap.MutableEntry<String, String>> {
        TODO("Not yet implemented")
    }

    override fun toString(toSuppress: Collection<String>?) = raw.toString(toSuppress)

    override fun get(name: String) = raw.header(name)

    override fun get(name: CharSequence) = raw.header(name.toString())

    override fun get(name: CharSequence, defaultValue: String?) = raw.header(name.toString()) ?: defaultValue

    override fun getInt(name: CharSequence, defaultValue: Int): Int = raw.header(name.toString())?.toIntOrNull() ?: defaultValue

    override fun getLong(name: String, defaultValue: Long) = raw.header(name.toString())?.toLongOrNull() ?: defaultValue

    override fun getFloat(name: String, defaultValue: Float) = raw.header(name.toString())?.toFloatOrNull() ?: defaultValue

    override fun getDouble(name: String, defaultValue: Double) = raw.header(name.toString())?.toDoubleOrNull() ?: defaultValue

    override fun getBoolean(name: String): Boolean {
        val s = get(name) ?: return false
        return NettyRequestParameters.isTruthy(s)
    }

    override fun getTimeMillis(name: CharSequence): Long? {
        val s = get(name) ?: return null
        return Mutils.fromHttpDate(s).time
    }

    override fun getTimeMillis(name: CharSequence, defaultValue: Long) = getTimeMillis(name) ?: defaultValue

    override fun getAll(name: String): List<String> = raw.getAll(name)

    override fun getAll(name: CharSequence): List<String> = getAll(name.toString())

    override fun entries(): List<Map.Entry<String, String>> = raw.all().map { PairEntryAdaptor(it) }

    override fun contains(name: String) = raw.hasHeader(name.lowercase())

    override fun contains(name: CharSequence) = contains(name.toString())

    override fun contains(name: String, value: String, ignoreCase: Boolean): Boolean {
        val lowerName = name.lowercase()
        return raw.all().any { it.first == lowerName && it.second.equals(value, ignoreCase) }
    }

    override fun contains(name: CharSequence, value: CharSequence, ignoreCase: Boolean): Boolean {
        return contains(name.toString(), value, ignoreCase)
    }

    override fun isEmpty() = raw.size() == 0

    override fun size() = raw.size()

    override fun names(): Set<String> = raw.all().map { it.first }.toSet()

    override fun add(name: String, value: Any): Headers {
        raw.addHeader(name, value.toString())
        return this
    }

    override fun add(name: CharSequence, value: Any): Headers {
        return add(name.toString(), value)
    }

    override fun add(name: String, values: Iterable<*>): Headers {
        for (value in values) {
            add(name, value.toString())
        }
        return this
    }

    override fun add(name: CharSequence, values: Iterable<*>) = add(name.toString(), values)

    override fun add(headers: Headers): Headers {
        for (entry in headers.entries()) {
            raw.addHeader(entry.key, entry.value.toString())
        }
        return this
    }

    override fun addInt(name: CharSequence, value: Int): Headers {
        TODO("Not yet implemented")
    }

    override fun set(name: String, value: Any): Headers {
        raw.setHeader(name, value.toString())
        return this
    }

    override fun set(name: CharSequence, value: Any) = set(name.toString(), value)

    override fun set(name: String, values: MutableIterable<*>?): Headers {
        TODO("Not yet implemented")
    }

    override fun set(name: CharSequence, values: MutableIterable<*>?): Headers {
        TODO("Not yet implemented")
    }

    override fun set(headers: Headers?): Headers {
        TODO("Not yet implemented")
    }

    override fun setAll(headers: Headers?): Headers {
        TODO("Not yet implemented")
    }

    override fun setInt(name: CharSequence, value: Int): Headers {
        TODO("Not yet implemented")
    }

    override fun remove(name: String): Headers {
        TODO("Not yet implemented")
    }

    override fun remove(name: CharSequence): Headers {
        TODO("Not yet implemented")
    }

    override fun clear(): Headers {
        TODO("Not yet implemented")
    }

    override fun containsValue(name: CharSequence, value: CharSequence, ignoreCase: Boolean): Boolean {
        return containsValue(name.toString(), value.toString(), ignoreCase)
    }

    override fun hasBody(): Boolean {
        val cl = raw.contentLength()
        return raw.hasChunkedBody() || (cl != null && cl > 0L)
    }

    override fun accept(): List<ParameterizedHeaderWithValue> {
        return ParameterizedHeaderWithValue.fromString(get(HeaderNames.ACCEPT))
    }

    override fun acceptCharset(): List<ParameterizedHeaderWithValue> {
        return ParameterizedHeaderWithValue.fromString(get(HeaderNames.ACCEPT_CHARSET))
    }

    override fun acceptEncoding(): List<ParameterizedHeaderWithValue> {
        return ParameterizedHeaderWithValue.fromString(get(HeaderNames.ACCEPT_ENCODING))
    }

    override fun forwarded(): List<ForwardedHeader> {
        return ForwardedHeader.fromString(get(HeaderNames.FORWARDED))
    }

    override fun acceptLanguage(): List<ParameterizedHeaderWithValue> {
        return ParameterizedHeaderWithValue.fromString(get(HeaderNames.ACCEPT_LANGUAGE))
    }

    override fun cacheControl(): ParameterizedHeader {
        return ParameterizedHeader.fromString(get(HeaderNames.CACHE_CONTROL))
    }

    override fun contentType(): MediaType? {
        val mt = get(HeaderNames.CONTENT_TYPE) ?: return null
        return MediaType.valueOf(mt)
    }

    internal fun writeTo(out: OutputStream) {
        for (header in raw.all()) {
            out.write(header.first.headerBytes())
            out.write(COLON_SP)
            out.write(header.second.headerBytes())
            out.write(CRLF)
        }
    }
}

internal class PairEntryAdaptor<N,V>(val pair: Pair<N,V>) : Map.Entry<N,V> {
    override val key: N
        get() = pair.first
    override val value: V
        get() = pair.second

}