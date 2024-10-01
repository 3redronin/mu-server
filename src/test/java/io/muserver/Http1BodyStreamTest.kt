package io.muserver

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.sameInstance
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.*

class Http1BodyStreamTest {

    @Test
    fun `messages are converted to byte reads`() {
        val message = "Hello, world".toByteArray(StandardCharsets.UTF_8)
        streamOf(MessageBodyBit(message, 0, message.size, false), EndOfBodyBit).use { stream ->
            val myBuffer = ByteArray(message.size + 10)
            val read = stream.read(myBuffer, 5, message.size + 5)
            assertThat(read, equalTo(message.size))
            assertThat(stream.read(myBuffer), equalTo(-1))
            assertThat(String(myBuffer, 5, message.size), equalTo("Hello, world"))
        }
    }

    @Test
    fun `if the buffer length is too small then multiple reads occur`() {
        val message = "Hello, world".toByteArray(StandardCharsets.UTF_8)
        streamOf(MessageBodyBit(message, 0, message.size, false), EndOfBodyBit).use { stream ->
            val myBuffer = ByteArray(7)
            assertThat(stream.read(myBuffer), equalTo(7))
            assertThat(String(myBuffer, 0, 7), equalTo("Hello, "))
            assertThat(stream.read(myBuffer, 0, 1), equalTo(1))
            assertThat(String(myBuffer, 0, 1), equalTo("w"))
            assertThat(stream.read(myBuffer, 1, 4), equalTo(4))
            assertThat(stream.read(myBuffer), equalTo(-1))
            assertThat(String(myBuffer, 0, 5), equalTo("world"))
        }
    }


    @Test
    fun `messages can be read a byte at a time with separate end of body message`() {
        val message1 = "Hi ".toByteArray(StandardCharsets.UTF_8).toBodyMessage(false)
        val message2 = "world".toByteArray(StandardCharsets.UTF_8).toBodyMessage(false)
        streamOf(message1, message2, EndOfBodyBit).use { stream ->
            assertThat(readStringByteByByte(stream), equalTo("Hi world"))
        }
    }

    @Test
    fun `messages can be read a byte at a time with last message`() {
        val message1 = "Hi ".toByteArray(StandardCharsets.UTF_8).toBodyMessage(false)
        val message2 = "world".toByteArray(StandardCharsets.UTF_8).toBodyMessage(true)
        streamOf(message1, message2).use { stream ->
            assertThat(readStringByteByByte(stream), equalTo("Hi world"))
        }
    }

    @Test
    fun `messages can be read a byte at a time with an empty end message`() {
        val message1 = "Hi ".toByteArray(StandardCharsets.UTF_8).toBodyMessage(false)
        val message2 = "world".toByteArray(StandardCharsets.UTF_8).toBodyMessage(false)
        val message3 = "".toByteArray(StandardCharsets.UTF_8).toBodyMessage(true)
        streamOf(message1, message2, message3).use { stream ->
            assertThat(readStringByteByByte(stream), equalTo("Hi world"))
        }
    }

    @Test
    fun `closing consumes and discards any remaining body bits with empty last body`() {
        val message1 = "Hi ".toByteArray(StandardCharsets.UTF_8).toBodyMessage(false)
        val message2 = "world".toByteArray(StandardCharsets.UTF_8).toBodyMessage(false)
        val message3 = "".toByteArray(StandardCharsets.UTF_8).toBodyMessage(true)
        val message4 = "campire".toByteArray(StandardCharsets.UTF_8).toBodyMessage(false)
        val parser = HardCodedMessageReader(LinkedList(listOf(message1, message2, message3, message4)))
        Http1BodyStream(parser, Long.MAX_VALUE).use { stream ->
            stream.read()
        }
        assertThat(parser.readNext(), sameInstance(message4))
    }
    @Test
    fun `closing consumes and discards any remaining body bits with non-empty last body`() {
        val message1 = "Hi ".toByteArray(StandardCharsets.UTF_8).toBodyMessage(false)
        val message2 = "world".toByteArray(StandardCharsets.UTF_8).toBodyMessage(false)
        val message3 = "wide camp champions".toByteArray(StandardCharsets.UTF_8).toBodyMessage(true)
        val message4 = "campire".toByteArray(StandardCharsets.UTF_8).toBodyMessage(false)
        val parser = HardCodedMessageReader(LinkedList(listOf(message1, message2, message3, message4)))
        Http1BodyStream(parser, Long.MAX_VALUE).use { stream ->
            stream.read()
        }
        assertThat(parser.readNext(), sameInstance(message4))
    }

    @Test
    fun `closing consumes and discards any remaining body bits with EOF message`() {
        val message1 = "Hi ".toByteArray(StandardCharsets.UTF_8).toBodyMessage(false)
        val message2 = "world".toByteArray(StandardCharsets.UTF_8).toBodyMessage(false)
        val message3 = EndOfBodyBit
        val message4 = "campire".toByteArray(StandardCharsets.UTF_8).toBodyMessage(false)
        val parser = HardCodedMessageReader(LinkedList(listOf(message1, message2, message3, message4)))
        Http1BodyStream(parser, Long.MAX_VALUE).use { stream ->
            stream.read()
        }
        assertThat(parser.readNext(), sameInstance(message4))
    }



    private fun readStringByteByByte(stream: Http1BodyStream): String? {
        var b: Int
        val baos = ByteArrayOutputStream()
        while (stream.read().also { b = it } != -1) {
            baos.write(b)
        }
        val receivedString = baos.toString(StandardCharsets.UTF_8)
        return receivedString
    }

    private fun streamOf(vararg bits: Http1ConnectionMsg): Http1BodyStream {
        val q : Queue<Http1ConnectionMsg> = LinkedList()
        bits.forEach { q.add(it) }
        return Http1BodyStream(HardCodedMessageReader(q), Long.MAX_VALUE)
    }

}

private fun ByteArray.toBodyMessage(isLast: Boolean) = MessageBodyBit(this, 0, this.size, isLast)

private class HardCodedMessageReader(private val bits: Queue<Http1ConnectionMsg>) : Http1MessageReader {
    override fun readNext(): Http1ConnectionMsg {
        return bits.poll() ?: EOFMsg
    }
}