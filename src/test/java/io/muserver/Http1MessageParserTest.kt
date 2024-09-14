package io.muserver

import io.muserver.Http1MessageParser.Companion.isTChar
import io.muserver.Mu3Headers.Companion.headerBytes
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
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

    @ParameterizedTest
    @ValueSource(ints = [0, 100])
    fun `chunked bodies where whole body in single buffer is fine`(bufferOffset: Int) {
        val parser = Http1MessageParser(HttpMessageType.REQUEST, ConcurrentLinkedQueue())
        val request = StringBuilder(" ".repeat(bufferOffset))
        request.append("""
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
        request.append(chunkSizeHex).append("\r\n").append(chunk).append("\r\n0\r\n\r\n")

        val bytes = request.toString().headerBytes()

        val actual = mutableListOf<String>()

        parser.feed(bytes, bufferOffset, bytes.size - bufferOffset, object : HttpMessageListener {
            override fun onHeaders(exchange: HttpMessageTemp) {
                val req = exchange as HttpRequestTemp
                actual.add("Got request ${req.method} ${req.url} ${req.httpVersion} with ${exchange.headers().size()} headers and body size ${req.bodyTransferSize()}")
            }

            override fun onBodyBytes(exchange: HttpMessageTemp, type: BodyBytesType, array: ByteArray, offset: Int, length: Int) {
                actual.add("Received $type bytes: off=$offset len=$length")
                if (type == BodyBytesType.CONTENT) {
                    val content = String(array, offset, length)
                    if (content != chunk) {
                        actual.add("Received content bytes: off=$offset len=$length with invalid content: $content")
                    }
                }
            }

            override fun onMessageEnded(exchange: HttpMessageTemp) {
                actual.add("Message ended")
            }

            override fun onError(exchange: HttpMessageTemp, error: Exception) {
                actual.add("Error: $error")
            }

        })

        assertThat("Events:\n\t" + actual.joinToString("\n\t"), actual, contains(
            "Got request GET /blah HTTP/1.1 with 9 headers and body size BodySize(type=CHUNKED, bytes=null)",
            "Received ENCODING bytes: off=${811+bufferOffset} len=10",
            "Received CONTENT bytes: off=${821+bufferOffset} len=7429",
            "Received ENCODING bytes: off=0 len=2", // this is from a CRLF buffer
            "Received ENCODING bytes: off=${8252+bufferOffset} len=3",
            "Received ENCODING bytes: off=0 len=2", // this is from a CRLF buffer
            "Message ended",
        ))
    }

    @Test
    fun `chunked bodies where chunk goes over byte buffer edge are fine`() {
        val parser = Http1MessageParser(HttpMessageType.REQUEST, ConcurrentLinkedQueue())
        val request = StringBuilder("""
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
        request.append(chunkSizeHex).append("\r\n").append(chunk).append("\r\n0\r\n\r\n")

        val wholeMessage = request.toString().headerBytes()
        val firstMessage = ByteArray(8192)
        wholeMessage.copyInto(firstMessage, 0, 0, firstMessage.size)

        val secondMessage = ByteArray((wholeMessage.size - firstMessage.size) + 1000) // offsetting by a thousand just to test offsets are fine
        wholeMessage.copyInto(secondMessage, 1000, firstMessage.size, wholeMessage.size)



        val receivedContent = ByteArrayOutputStream()

        val actual = mutableListOf<String>()


        val listener = object : HttpMessageListener {
            override fun onHeaders(exchange: HttpMessageTemp) {
                val req = exchange as HttpRequestTemp
                actual.add(
                    "Got request ${req.method} ${req.url} ${req.httpVersion} with ${
                        exchange.headers().size()
                    } headers and body size ${req.bodyTransferSize()}"
                )
            }

            override fun onBodyBytes(exchange: HttpMessageTemp, type: BodyBytesType, array: ByteArray, offset: Int, length: Int) {
                actual.add("Received $type bytes: off=$offset len=$length")
                if (type == BodyBytesType.CONTENT) {
                    receivedContent.write(array, offset, length)
                }
            }

            override fun onMessageEnded(exchange: HttpMessageTemp) {
                actual.add("Message ended")
            }

            override fun onError(exchange: HttpMessageTemp, error: Exception) {
                actual.add("Error: $error")
            }

        }
        parser.feed(firstMessage, 0, firstMessage.size, listener)
        parser.feed(secondMessage, 1000, secondMessage.size - 1000, listener)

        assertThat(receivedContent.toByteArray().toString(StandardCharsets.UTF_8), equalTo(chunk))

        assertThat("Events:\n\t" + actual.joinToString("\n\t"), actual, contains(
            "Got request GET /blah HTTP/1.1 with 9 headers and body size BodySize(type=CHUNKED, bytes=null)",
            "Received ENCODING bytes: off=811 len=10",
            "Received CONTENT bytes: off=821 len=7371",
            "Received CONTENT bytes: off=1000 len=58",
            "Received ENCODING bytes: off=0 len=2",
            "Received ENCODING bytes: off=1060 len=3",
            "Received ENCODING bytes: off=0 len=2",
            "Message ended",
        ))
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 2, 3, 11, 20, 1000])
    fun `chunked bodies can span multiple chunks`(bytesPerFeed: Int) {
        val parser = Http1MessageParser(HttpMessageType.REQUEST, ConcurrentLinkedQueue())
        val request = StringBuilder("""
            GET /blah HTTP/1.1\r
            content-type: text/plain;charset=utf-8\r
            transfer-encoding: chunked\r
            \r
            
        """.trimIndent().replace("\\r", "\r"))

        val chunk = "Hello world there oh yes"
        val chunkSizeHex = chunk.toByteArray().size.toHexString(HexFormat.UpperCase)
        request.append(chunkSizeHex).append(";chunkmetadata=blah;another=value\r\n").append(chunk).append("\r\n0\r\ntrailer: hello\r\n\r\n")

        val wholeMessage = request.toString().headerBytes()
        val receivedContent = ByteArrayOutputStream()
        val receivedBytes = ByteArrayOutputStream()
        val receivedTrailers = ByteArrayOutputStream()

        val actual = mutableListOf<String>()


        val listener = object : HttpMessageListener {
            override fun onHeaders(exchange: HttpMessageTemp) {
                val req = exchange as HttpRequestTemp
                actual.add(
                    "Got request ${req.method} ${req.url} ${req.httpVersion} with ${
                        exchange.headers().size()
                    } headers and body size ${req.bodyTransferSize()}"
                )
                req.writeTo(receivedBytes)
            }

            override fun onBodyBytes(exchange: HttpMessageTemp, type: BodyBytesType, array: ByteArray, offset: Int, length: Int) {
                receivedBytes.write(array, offset, length)
                if (type == BodyBytesType.CONTENT) {
                    receivedContent.write(array, offset, length)
                } else if (type == BodyBytesType.TRAILERS) {
                    receivedTrailers.write(array, offset, length)
                }
            }

            override fun onMessageEnded(exchange: HttpMessageTemp) {
                actual.add("Message ended")
            }

            override fun onError(exchange: HttpMessageTemp, error: Exception) {
                actual.add("Error: $error")
            }

        }

        for (i in wholeMessage.indices step bytesPerFeed) {
            val length = minOf(bytesPerFeed, wholeMessage.size - i)
            parser.feed(wholeMessage, i, length, listener)
        }

        assertThat(receivedContent.toByteArray().toString(StandardCharsets.UTF_8), equalTo(chunk))
        assertThat(receivedBytes.toByteArray().toString(StandardCharsets.UTF_8), equalTo(request.toString()))

        assertThat("Events:\n\t" + actual.joinToString("\n\t"), actual, contains(
            "Got request GET /blah HTTP/1.1 with 2 headers and body size BodySize(type=CHUNKED, bytes=null)",
            "Message ended",
        ))

        val trailers = Mu3Headers.parse(receivedTrailers.toByteArray())
        assertThat(trailers.size(), equalTo(1))
        assertThat(trailers.getAll("trailer"), contains("hello"))
    }

}