package io.muserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.ParseException;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(5)
class Http1MessageParserAvailableTest {
    @Test
    void pollingAndBlockingBodyReadsShareBufferedPipelineBytesWithoutWaitingForMoreInput() throws Exception {
        try (var input = new TransportInputBuffer(512, () -> { })) {
            var parser = new Http1MessageParser(HttpMessageType.REQUEST, new ArrayDeque<>(), input, 8192, 8192);
            assertNull(parser.readAvailable(input::readAvailable));
            input.offer(ByteBuffer.wrap("POST /first HTTP/1.1\r\nHost: localhost\r\nContent-Len".getBytes(US_ASCII)));
            assertNull(parser.readAvailable(input::readAvailable));
            assertNull(parser.readAvailable(input::readAvailable));
            input.offer(ByteBuffer.wrap(("gth: 3\r\n\r\nabcGET /second HTTP/1.1\r\nHost: localhost\r\n\r\n").getBytes(US_ASCII)));
            assertEquals("/first", ((HttpRequestTemp) parser.readAvailable(input::readAvailable)).requestTarget().toString());
            assertEquals("abc", new String(new Http1BodyStream(parser, 100).readAllBytes(), US_ASCII));
            assertEquals("/second", ((HttpRequestTemp) parser.readAvailable(bytes -> fail("Pipeline bytes were already read"))).requestTarget().toString());
            assertNull(parser.readAvailable(input::readAvailable));
            input.endOfInput();
            assertTrue(MessageBodyBit.isEof(parser.readAvailable(input::readAvailable)));
        }
    }

    @Test
    void partialHeaderEofAndSourceFailureAreTerminal() throws Exception {
        try (var input = new TransportInputBuffer(32, () -> { })) {
            var parser = new Http1MessageParser(HttpMessageType.REQUEST, new ArrayDeque<>(), input, 8192, 8192);
            input.offer(ByteBuffer.wrap("GET / HTTP/1.1\r\nHost:".getBytes(US_ASCII)));
            input.endOfInput();
            assertNull(parser.readAvailable(input::readAvailable));
            assertThrows(ParseException.class, () -> parser.readAvailable(input::readAvailable));
            assertThrows(ParseException.class, () -> parser.readAvailable(bytes -> fail("Terminal parser read again")));
        }
        try (var input = new TransportInputBuffer(32, () -> { })) {
            var parser = new Http1MessageParser(HttpMessageType.REQUEST, new ArrayDeque<>(), input, 8192, 8192);
            IOException failure = new IOException("source failed");
            input.fail(failure);
            assertSame(failure, assertThrows(IOException.class, () -> parser.readAvailable(input::readAvailable)));
            assertThrows(ParseException.class, () -> parser.readAvailable(bytes -> fail("Terminal parser read again")));
        }
    }

    @Test
    void eachPollReadsAtMostOneBufferEvenWhenAHeaderIsIncomplete() throws Exception {
        try (var input = new TransportInputBuffer(1, () -> { })) {
            var parser = new Http1MessageParser(HttpMessageType.REQUEST, new ArrayDeque<>(), input, 100_000, 100_000);
            AtomicInteger reads = new AtomicInteger();
            assertNull(parser.readAvailable(bytes -> {
                assertEquals(1, reads.incrementAndGet());
                byte[] prefix = "GET /".getBytes(US_ASCII);
                System.arraycopy(prefix, 0, bytes, 0, prefix.length);
                java.util.Arrays.fill(bytes, prefix.length, bytes.length, (byte) 'x');
                return bytes.length;
            }));
            assertEquals(1, reads.get());
        }
    }

    @Test
    void upgradeTransfersUnreadBytesAndPreventsFurtherParserReads() throws Exception {
        try (var input = new TransportInputBuffer(512, () -> { })) {
            var parser = new Http1MessageParser(HttpMessageType.REQUEST, new ArrayDeque<>(), input, 8192, 8192);
            input.offer(ByteBuffer.wrap("GET / HTTP/1.1\r\nHost: localhost\r\n\r\nnext-protocol".getBytes(US_ASCII)));
            assertInstanceOf(HttpRequestTemp.class, parser.readAvailable(input::readAvailable));
            ByteBuffer transferred = parser.takeInputForUpgrade();
            byte[] suffix = new byte[transferred.remaining()];
            transferred.get(suffix);
            assertEquals("next-protocol", new String(suffix, US_ASCII));
            assertTrue(transferred.capacity() > suffix.length);
            assertThrows(IllegalStateException.class, parser::readNext);
            assertThrows(IllegalStateException.class, () -> parser.readAvailable(input::readAvailable));
            assertThrows(IllegalStateException.class, parser::takeInputForUpgrade);
        }
    }
}
