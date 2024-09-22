package io.muserver

import io.muserver.Http1MessageParser.Companion.isTChar
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue

@OptIn(ExperimentalStdlibApi::class)
class Http1MessageParserTest {

    @Test
    fun tcharsAreValid() {
        val chars = arrayOf('!', '#', '$', '%', '&', '\'', '*', '+', '-', '.', '^', '_', '`', '|', '~', '0', '9', 'a', 'z', 'A', 'Z')
        for (char in chars) {
            assertThat(char.code.toByte().isTChar(), equalTo(true))
        }
        for (c in '0'..'9') {
            assertThat(c.code.toByte().isTChar(), equalTo(true))
        }

        for (c in 'a'..'z') {
            assertThat(c.code.toByte().isTChar(), equalTo(true))
        }
        for (c in 'A'..'Z') {
            assertThat(c.code.toByte().isTChar(), equalTo(true))
        }
        for (i in 0..32) {
            assertThat(i.toByte().isTChar(), equalTo(false))
        }
        val nots = arrayOf(34, 40, 41, 44, 47, 58, 59, 60, 61, 62, 63, 64, 91, 92, 93, 123, 125)
        for (not in nots) {
            assertThat(34.toByte().isTChar(), equalTo(false))
        }

        for (i in 127..256) {
            assertThat(i.toByte().isTChar(), equalTo(false))
        }
    }

    @Test
    fun `empty header values are ignored`() {
        val requestString = StringBuilder()
        requestString.append("""
            GET /blah HTTP/1.1\r
            accept-encoding:\r
            accept-encoding-2: \r
            accept-encoding-3:  \r
            content-length: 0\r
            \r
            
        """.trimIndent().replace("\\r", "\r"))
        val bais = ByteArrayInputStream(requestString.toString().toByteArray(StandardCharsets.UTF_8))
        val parser = Http1MessageParser(HttpMessageType.REQUEST, ConcurrentLinkedQueue(), bais, 8192, 8192)
        val req = parser.readNext() as HttpRequestTemp
        assertThat(req.headers().toString(), req.headers().size(), equalTo(1))
        assertThat(req.headers().getAll("content-length"), contains("0"))
    }

    @Test
    fun `chunked bodies where whole body in single buffer is fine`() {
        val requestString = StringBuilder()
        requestString.append("""
            GET /blah HTTP/1.1\r
            content-type: text/plain;charset=utf-8\r
            transfer-encoding: chunked\r
            some-header1: some-value some-value some-value some-value some-value some-value some-value some-value\r
            some-header2: some-value some-value some-value some-value some-value some-value some-value some-value\r
            some-header3: some-value some-value some-value some-value some-value some-value some-value some-value\r
            some-header4: some-value some-value some-value some-value some-value some-value some-value some-value\r
            some-header5: some-value some-value some-value some-value some-value some-value some-value some-value\r
            some-header6: some-value some-value some-value some-value some-value some-value some-value some-value\r
            some-header7: some-value some-value some-value some-value some-value some-value some-value some-value\r
            \r
            
        """.trimIndent().replace("\\r", "\r"))

        val chunk = "!".repeat(7429)
        val chunkSizeHex = chunk.toByteArray().size.toHexString(HexFormat.UpperCase)
        requestString.append(chunkSizeHex).append("\r\n").append(chunk).append("\r\n0\r\n\r\n")


        val bais = ByteArrayInputStream(requestString.toString().toByteArray(StandardCharsets.UTF_8))
        val parser = Http1MessageParser(HttpMessageType.REQUEST, ConcurrentLinkedQueue(), bais, 8192, 8192)

        val request = parser.readNext() as HttpRequestTemp
        assertThat(request.method, equalTo(Method.GET))
        assertThat(request.url, equalTo("/blah"))
        assertThat(request.httpVersion, equalTo(HttpVersion.HTTP_1_1))
        val body = parser.readNext() as MessageBodyBit
        var bodyContent = String(body.bytes, body.offset, body.length)
        assertThat(body.isLast, equalTo(false))
        val body2 = parser.readNext() as MessageBodyBit
        bodyContent += String(body2.bytes, body2.offset, body2.length)
        assertThat(bodyContent, equalTo(chunk))
        assertThat(body2.isLast, equalTo(false))
        assertThat(parser.readNext(), instanceOf(EndOfBodyBit::class.java))
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 2, 3, 11, 20, 1000])
    fun `chunked bodies can span multiple chunks`(maxBytesPerRead: Int) {
        val request = StringBuilder("""
            GET /blah HTTP/1.1\r
            content-type: text/plain;charset=utf-8\r
            transfer-encoding: chunked\r
            \r
            
        """.trimIndent().replace("\\r", "\r"))

        val chunk = "Hello world there oh yes"
        val chunkSizeHex = chunk.toByteArray().size.toHexString(HexFormat.UpperCase)
        request.append(chunkSizeHex).append(";chunkmetadata=blah;another=value\r\n").append(chunk).append("\r\n0\r\ntrailer: hello\r\n\r\n")

        val wholeMessage = request.toString().toByteArray(StandardCharsets.UTF_8)
        val inputStream = MaxReadLengthInputStream(ByteArrayInputStream(wholeMessage), maxBytesPerRead)
        val parser = Http1MessageParser(HttpMessageType.REQUEST, ConcurrentLinkedQueue(), inputStream, 8192, 8192)

        val req = parser.readNext() as HttpRequestTemp
        assertThat(req.method, equalTo(Method.GET))
        assertThat(req.url, equalTo("/blah"))
        assertThat(req.httpVersion, equalTo(HttpVersion.HTTP_1_1))

        var bit : Http1ConnectionMsg
        val contentReceived = ByteArrayOutputStream()
        while (parser.readNext().also { bit = it } is MessageBodyBit) {
            val body = bit as MessageBodyBit
            contentReceived.write(body.bytes, body.offset, body.length)
        }
        assertThat(contentReceived.toString(StandardCharsets.UTF_8), equalTo(chunk))
        assertThat(bit, instanceOf(EndOfBodyBit::class.java))


//        val trailers = Mu3Headers.parse(receivedTrailers.toByteArray())
//        assertThat(trailers.size(), equalTo(1))
//        assertThat(trailers.getAll("trailer"), contains("hello"))
    }

}

class MaxReadLengthInputStream(underlying: InputStream, maxBytesPerRead: Int) : FilterInputStream(underlying) {

    private val temp = ByteArray(maxBytesPerRead)

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len > temp.size) {
            val actual = super.read(temp, 0, temp.size)
            temp.copyInto(b, off, 0, actual)
            return actual
        } else return super.read(b, off, len)
    }
}
