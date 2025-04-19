package io.muserver;

import org.hamcrest.MatcherAssert;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.Queue;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.sameInstance;

class Http1BodyStreamTest {

    @Test
    void messagesAreConvertedToByteReads() throws IOException {
        byte[] message = "Hello, world".getBytes(StandardCharsets.UTF_8);
        try (Http1BodyStream stream = streamOf(
            new MessageBodyBit(message, 0, message.length, false),
            MessageBodyBit.EndOfBodyBit)) {

            byte[] myBuffer = new byte[message.length + 10];
            int read = stream.read(myBuffer, 5, message.length + 5);
            MatcherAssert.assertThat(read, equalTo(message.length));
            MatcherAssert.assertThat(stream.read(myBuffer), equalTo(-1));
            MatcherAssert.assertThat(new String(myBuffer, 5, message.length), equalTo("Hello, world"));
        }
    }

    @Test
    void ifTheBufferLengthIsTooSmallThenMultipleReadsOccur() throws IOException {
        byte[] message = "Hello, world".getBytes(StandardCharsets.UTF_8);
        try (Http1BodyStream stream = streamOf(
            new MessageBodyBit(message, 0, message.length, false),
            MessageBodyBit.EndOfBodyBit)) {

            byte[] myBuffer = new byte[7];
            MatcherAssert.assertThat(stream.read(myBuffer), equalTo(7));
            MatcherAssert.assertThat(new String(myBuffer, 0, 7), equalTo("Hello, "));
            MatcherAssert.assertThat(stream.read(myBuffer, 0, 1), equalTo(1));
            MatcherAssert.assertThat(new String(myBuffer, 0, 1), equalTo("w"));
            MatcherAssert.assertThat(stream.read(myBuffer, 1, 4), equalTo(4));
            MatcherAssert.assertThat(stream.read(myBuffer), equalTo(-1));
            MatcherAssert.assertThat(new String(myBuffer, 0, 5), equalTo("world"));
        }
    }

    @Test
    void messagesCanBeReadAByteAtATimeWithSeparateEndOfBodyMessage() throws IOException {
        MessageBodyBit message1 = toBodyMessage("Hi ".getBytes(StandardCharsets.UTF_8), false);
        MessageBodyBit message2 = toBodyMessage("world".getBytes(StandardCharsets.UTF_8), false);
        try (Http1BodyStream stream = streamOf(message1, message2, MessageBodyBit.EndOfBodyBit)) {
            MatcherAssert.assertThat(readStringByteByByte(stream), equalTo("Hi world"));
        }
    }

    @Test
    void messagesCanBeReadAByteAtATimeWithLastMessage() throws IOException {
        MessageBodyBit message1 = toBodyMessage("Hi ".getBytes(StandardCharsets.UTF_8), false);
        MessageBodyBit message2 = toBodyMessage("world".getBytes(StandardCharsets.UTF_8), true);
        try (Http1BodyStream stream = streamOf(message1, message2)) {
            MatcherAssert.assertThat(readStringByteByByte(stream), equalTo("Hi world"));
        }
    }

    @Test
    void messagesCanBeReadAByteAtATimeWithAnEmptyEndMessage() throws IOException {
        MessageBodyBit message1 = toBodyMessage("Hi ".getBytes(StandardCharsets.UTF_8), false);
        MessageBodyBit message2 = toBodyMessage("world".getBytes(StandardCharsets.UTF_8), false);
        MessageBodyBit message3 = toBodyMessage("".getBytes(StandardCharsets.UTF_8), true);
        try (Http1BodyStream stream = streamOf(message1, message2, message3)) {
            MatcherAssert.assertThat(readStringByteByByte(stream), equalTo("Hi world"));
        }
    }

    @Test
    void closingConsumesAndDiscardsAnyRemainingBodyBitsWithEmptyLastBody() throws IOException {
        MessageBodyBit message1 = toBodyMessage("Hi ".getBytes(StandardCharsets.UTF_8), false);
        MessageBodyBit message2 = toBodyMessage("world".getBytes(StandardCharsets.UTF_8), false);
        MessageBodyBit message3 = toBodyMessage("".getBytes(StandardCharsets.UTF_8), true);
        MessageBodyBit message4 = toBodyMessage("campire".getBytes(StandardCharsets.UTF_8), false);
        HardCodedMessageReader parser = new HardCodedMessageReader(
            new LinkedList<>(java.util.Arrays.asList(message1, message2, message3, message4)));
        Http1BodyStream.State bodyState;
        try (Http1BodyStream stream = new Http1BodyStream(parser, Long.MAX_VALUE)) {
            stream.read();
            bodyState = stream.discardRemaining(false);
        }
        MatcherAssert.assertThat(parser.readNext(), sameInstance(message4));
        MatcherAssert.assertThat(bodyState, equalTo(Http1BodyStream.State.EOF));
    }

    @Test
    void discardingConsumesAndDiscardsAnyRemainingBodyBitsWithNonEmptyLastBody() throws IOException {
        MessageBodyBit message1 = toBodyMessage("Hi ".getBytes(StandardCharsets.UTF_8), false);
        MessageBodyBit message2 = toBodyMessage("world".getBytes(StandardCharsets.UTF_8), false);
        MessageBodyBit message3 = toBodyMessage("wide camp champions".getBytes(StandardCharsets.UTF_8), true);
        MessageBodyBit message4 = toBodyMessage("campire".getBytes(StandardCharsets.UTF_8), false);
        HardCodedMessageReader parser = new HardCodedMessageReader(
            new LinkedList<>(java.util.Arrays.asList(message1, message2, message3, message4)));
        try (Http1BodyStream stream = new Http1BodyStream(parser, Long.MAX_VALUE)) {
            stream.read();
            MatcherAssert.assertThat(stream.discardRemaining(false), equalTo(Http1BodyStream.State.EOF));
        }
        MatcherAssert.assertThat(parser.readNext(), sameInstance(message4));
    }

    @Test
    void closingConsumesAndDiscardsAnyRemainingBodyBitsWithEOFMessage() throws IOException {
        MessageBodyBit message1 = toBodyMessage("Hi ".getBytes(StandardCharsets.UTF_8), false);
        MessageBodyBit message2 = toBodyMessage("world".getBytes(StandardCharsets.UTF_8), false);
        MessageBodyBit message3 = MessageBodyBit.EndOfBodyBit;
        MessageBodyBit message4 = toBodyMessage("campire".getBytes(StandardCharsets.UTF_8), false);
        HardCodedMessageReader parser = new HardCodedMessageReader(
            new LinkedList<>(java.util.Arrays.asList(message1, message2, message3, message4)));
        try (Http1BodyStream stream = new Http1BodyStream(parser, Long.MAX_VALUE)) {
            stream.read();
            MatcherAssert.assertThat(stream.discardRemaining(false), equalTo(Http1BodyStream.State.EOF));
        }
        MatcherAssert.assertThat(parser.readNext(), sameInstance(message4));
    }

    private String readStringByteByByte(Http1BodyStream stream) {
        int b;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            while ((b = stream.read()) != -1) {
                baos.write(b);
            }
            return baos.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return null;
        }
    }

    private Http1BodyStream streamOf(Http1ConnectionMsg... bits) {
        Queue<Http1ConnectionMsg> q = new LinkedList<>();
        for (Http1ConnectionMsg bit : bits) {
            q.add(bit);
        }
        return new Http1BodyStream(new HardCodedMessageReader(q), Long.MAX_VALUE);
    }

    private MessageBodyBit toBodyMessage(byte[] bytes, boolean isLast) {
        return new MessageBodyBit(bytes, 0, bytes.length, isLast);
    }

    private static class HardCodedMessageReader implements Http1MessageReader {
        private final Queue<Http1ConnectionMsg> bits;

        HardCodedMessageReader(Queue<Http1ConnectionMsg> bits) {
            this.bits = bits;
        }

        @Override
        public Http1ConnectionMsg readNext() {
            Http1ConnectionMsg msg = bits.poll();
            return msg != null ? msg : MessageBodyBit.EOFMsg;
        }
    }
}