package io.muserver

import jakarta.ws.rs.core.MediaType
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.*

internal class Mu3Headers(
    /**
     * Headers where all header names are lowercase
     */
    private val headers: MutableList<Pair<String, String>> = mutableListOf(),
) : Headers {
    override fun iterator(): MutableIterator<MutableMap.MutableEntry<String, String>> {
        val iterator = headers.map { MutablePairEntryAdaptor(it) }.iterator()
        var lastElement : Pair<String,String>? = null
        return object : MutableIterator<MutableMap.MutableEntry<String, String>> {
            override fun hasNext() = iterator.hasNext()
            override fun next(): MutableMap.MutableEntry<String, String> {
                val next = iterator.next()
                lastElement = next.pair
                return next
            }
            override fun remove() {
                if (lastElement == null) throw IllegalStateException("No element to remove. Call next() first.")
                headers.remove(lastElement)
                lastElement = null
            }

        }
    }

    fun closeConnection(version: HttpVersion) = when (version) {
        HttpVersion.HTTP_1_1 -> containsValue(HeaderNames.CONNECTION, HeaderValues.CLOSE.toString(), true)
        // TODO treat the connection header in http1.0 as a list field value https://httpwg.org/specs/rfc9110.html#rfc.section.5.6.1
        HttpVersion.HTTP_1_0 -> !containsValue(HeaderNames.CONNECTION, HeaderValues.KEEP_ALIVE.toString(), true)
    }

    override fun get(name: String): String? {
        val lowered = name.lowercase()
        return headers.firstOrNull { it.first == lowered }?.second
    }

    override fun get(name: CharSequence): String? = get(name.toString())

    override fun get(name: CharSequence, defaultValue: String?): String? = get(name.toString()) ?: defaultValue

    override fun getInt(name: CharSequence, defaultValue: Int): Int = get(name.toString())?.toIntOrNull() ?: defaultValue

    override fun getLong(name: String, defaultValue: Long): Long = get(name)?.toLongOrNull() ?: defaultValue

    override fun getFloat(name: String, defaultValue: Float): Float = get(name)?.toFloatOrNull() ?: defaultValue

    override fun getDouble(name: String, defaultValue: Double): Double = get(name)?.toDoubleOrNull() ?: defaultValue

    override fun getBoolean(name: String): Boolean {
        val s = get(name) ?: return false
        return NettyRequestParameters.isTruthy(s)
    }

    override fun getTimeMillis(name: CharSequence): Long? {
        val s = get(name) ?: return null
        return Mutils.fromHttpDate(s).time
    }

    override fun getTimeMillis(name: CharSequence, defaultValue: Long) = getTimeMillis(name) ?: defaultValue

    override fun getAll(name: String): List<String> {
        val lowered = name.lowercase()
        return headers.filter { it.first == lowered }.map { it.second }
    }

    override fun getAll(name: CharSequence): List<String> = getAll(name.toString())

    override fun entries(): List<Map.Entry<String, String>> = headers.map { PairEntryAdaptor(it) }


    override fun contains(name: String) : Boolean {
        val lowered = name.lowercase()
        return headers.any { it.first == lowered }
    }

    override fun contains(name: CharSequence) = contains(name.toString())

    override fun contains(name: String, value: String, ignoreCase: Boolean): Boolean {
        val lowerName = name.lowercase()
        return headers.any { it.first == lowerName && it.second.equals(value, ignoreCase) }
    }

    override fun contains(name: CharSequence, value: CharSequence, ignoreCase: Boolean): Boolean {
        return contains(name.toString(), value, ignoreCase)
    }

    override fun isEmpty() = headers.isEmpty()

    override fun size() = headers.size

    override fun names(): Set<String> = headers.map { it.first }.toSet()

    override fun add(name: String, value: Any): Headers {
        headers.add(Pair(name, value.toString()))
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
            this.headers.add(Pair(entry.key, entry.value.toString()))
        }
        return this
    }

    override fun addInt(name: CharSequence, value: Int): Headers {
        TODO("Not yet implemented")
    }

    override fun set(name: String, value: Any): Headers {
        val lower = name.lowercase()
        headers.removeAll { it.first == lower }
        add(lower, value)
        return this
    }

    override fun set(name: CharSequence, value: Any) = set(name.toString(), value)

    override fun set(name: String, values: MutableIterable<*>?): Headers {
        TODO("Not yet implemented")
    }

    override fun set(name: CharSequence, values: MutableIterable<*>?): Headers {
        TODO("Not yet implemented")
    }

    override fun set(headers: Headers): Headers {
        clear()
        for (header in headers) {
            add(header.key, header.value)
        }
        return this
    }

    override fun setAll(headers: Headers?): Headers {
        TODO("Not yet implemented")
    }

    override fun setInt(name: CharSequence, value: Int): Headers {
        TODO("Not yet implemented")
    }

    override fun remove(name: String): Headers {
        val lowered = name.lowercase()
        headers.removeAll { it.first == lowered }
        return this
    }

    override fun remove(name: CharSequence): Headers {
        return remove(name.toString())
    }

    override fun clear(): Headers {
        headers.clear()
        return this
    }

    override fun containsValue(name: CharSequence, value: CharSequence, ignoreCase: Boolean): Boolean {
        return headers.any { h -> name.contentEquals(h.first, true) && value.contentEquals(h.second, ignoreCase) }
    }
    fun contentLength(): Long? = get("content-length")?.toLongOrNull()
    internal fun hasChunkedBody() = containsValue("transfer-encoding", "chunked", false)

    override fun hasBody(): Boolean {
        val cl = contentLength()
        return hasChunkedBody() || (cl != null && cl > 0L)
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
        for (header in headers) {
            out.write(header.first.headerBytes())
            out.write(COLON_SP)
            out.write(header.second.headerBytes())
            out.write(CRLF)
        }
    }

    override fun toString(): String = toString(null)
    override fun toString(toSuppress: Collection<String>?): String {
        val sup = toSuppress ?: setOf("authorization", "cookie", "set-cookie")
        val sb = StringBuilder("HttpHeaders[")
        var first = true
        for (header in headers) {
            if (!first) sb.append(", ")
            val name = header.first
            val suppress = sup.any { it.equals(name, ignoreCase = true) }
            val value = if (suppress) "(hidden)" else header.second
            sb.append(name).append(": ").append(value)
            first = false
        }
        sb.append("]")
        return sb.toString()
    }

    companion object {
        internal fun String.headerBytes() = this.toByteArray(StandardCharsets.US_ASCII)

        @JvmStatic
        fun newWithDate(): Mu3Headers {
            val headers = Mu3Headers()
            headers.set(HeaderNames.DATE, Mutils.toHttpDate(Date()))
            return headers
        }

    }


}

internal class PairEntryAdaptor<N,V>(private val pair: Pair<N,V>) : Map.Entry<N,V> {
    override val key: N
        get() = pair.first
    override val value: V
        get() = pair.second

}

internal class MutablePairEntryAdaptor<N,V>(var pair: Pair<N,V>) : MutableMap.MutableEntry<N,V> {
    override val key: N
        get() = pair.first
    override val value: V
        get() = pair.second

    override fun setValue(newValue: V): V {
        val oldPair = pair
        pair = Pair(oldPair.first, newValue)
        return oldPair.second
    }

}