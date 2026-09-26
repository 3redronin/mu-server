package io.muserver;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.*;

/** RFC 9112 sections 5, 6.3 and 7.1: deterministic reads at message boundaries. */
class Http1FramingConformanceTest {
    @ParameterizedTest
    @ValueSource(strings = {
        "Content-Length:", "Content-Length: \t ",
        "Content-Length:\r\nTransfer-Encoding: chunked",
        "Content-Length: \t\r\nTransfer-Encoding: chunked",
        "Transfer-Encoding:\r\nContent-Length: 0",
        "Transfer-Encoding: \t \r\nContent-Length: 0",
        "Transfer-Encoding:\r\nTransfer-Encoding: chunked",
        "Content-Length:\r\nContent-Length: 0"
    })
    void emptyFramingFieldsCannotDisappearBeforeValidation(String headers) throws Exception {
        Http1MessageParser parser = parser("POST /first HTTP/1.1\r\nHost: localhost\r\n" + headers
            + "\r\n\r\nGET /next HTTP/1.1\r\nHost: localhost\r\n\r\n", 1);
        HttpRequestTemp request = (HttpRequestTemp) parser.readNext();
        HttpException rejection = request.getRejectRequest();
        assertNotNull(rejection, "Empty framing fields must not be ignored");
        assertEquals(400, rejection.status().code());
        assertEquals("close", rejection.responseHeaders().get("connection"));
    }

    private static Http1MessageParser parser(String wire, int readSize) {
        ByteArrayInputStream input = new ByteArrayInputStream(wire.getBytes(StandardCharsets.ISO_8859_1)) {
            @Override public synchronized int read(byte[] bytes, int offset, int length) {
                return super.read(bytes, offset, Math.min(length, readSize));
            }
        };
        return new Http1MessageParser(HttpMessageType.REQUEST, new ConcurrentLinkedQueue<>(), input, 8192, 8192);
    }

    private static String body(Http1MessageParser parser) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        // Bounded assertion loop: these fixtures contain at most a dozen body octets.
        for (int i = 0; i < 32; i++) {
            Http1ConnectionMsg next = parser.readNext();
            assertTrue(next instanceof MessageBodyBit, "Expected body data, got " + next);
            MessageBodyBit bit = (MessageBodyBit) next;
            assertNotSame(MessageBodyBit.EOFMsg, bit, "Unexpected end of input");
            bytes.write(bit.bytes(), bit.offset(), bit.length());
            if (bit.isLast()) return bytes.toString(StandardCharsets.ISO_8859_1);
        }
        throw new AssertionError("Body did not finish");
    }

    private static void nextRequest(Http1MessageParser parser) throws Exception {
        HttpRequestTemp next = (HttpRequestTemp) parser.readNext();
        assertEquals("/next", next.getUrl());
        assertNull(next.getRejectRequest());
        assertSame(MessageBodyBit.EOFMsg, parser.readNext());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 7, 8192})
    void fixedLengthBodyDoesNotConsumeFollowingRequest(int readSize) throws Exception {
        Http1MessageParser parser = parser("POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 5\r\n\r\na\r\nb!"
            + "GET /next HTTP/1.1\r\nHost: localhost\r\n\r\n", readSize);
        assertEquals(Method.POST, ((HttpRequestTemp) parser.readNext()).getMethod());
        assertEquals("a\r\nb!", body(parser));
        nextRequest(parser);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 7, 8192})
    void chunkExtensionsAndMixedCaseHexPreserveMessageBoundary(int readSize) throws Exception {
        Http1MessageParser parser = parser("POST / HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n"
            + "1;!\r\nx\r\na;name=value;flag\r\n0123456789\r\nA; quoted = \"hello \\\"world\\\"\"; empty=\"\"\r\nabcdefghij\r\n0;final=yes\r\n\r\n"
            + "GET /next HTTP/1.1\r\nHost: localhost\r\n\r\n", readSize);
        parser.readNext();
        assertEquals("x0123456789abcdefghij", body(parser));
        nextRequest(parser);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 7, 8192})
    void bareLfInChunkExtensionDoesNotTerminateTheChunkLine(int readSize) throws Exception {
        Http1MessageParser parser = parser("POST / HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n"
            + "3D;!\n" + "A".repeat(61) + "\r\n0\r\n\r\n"
            + "GET /next HTTP/1.1\r\nHost: localhost\r\n\r\n", readSize);

        assertEquals(Method.POST, ((HttpRequestTemp) parser.readNext()).getMethod());
        assertThrows(ParseException.class, parser::readNext);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 7, 8192})
    void trailersEndBeforeTheNextRequestAndAreTakenOnlyOnce(int readSize) throws Exception {
        Http1MessageParser parser = parser("POST / HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n"
            + "1\r\nx\r\n0\r\nX-Result: first\r\nX-Result: second\r\n\r\n"
            + "GET /next HTTP/1.1\r\nHost: localhost\r\n\r\n", readSize);
        parser.readNext();
        assertEquals("x", body(parser));
        FieldBlock trailers = parser.takeTrailers();
        assertNotNull(trailers);
        assertEquals(List.of("first", "second"), trailers.getAll("x-result"));
        assertNull(parser.takeTrailers());
        nextRequest(parser);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 7, 8192})
    void zeroContentLengthEndsBeforeFollowingRequest(int readSize) throws Exception {
        Http1MessageParser parser = parser("POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n"
            + "GET /next HTTP/1.1\r\nHost: localhost\r\n\r\n", readSize);
        parser.readNext();
        nextRequest(parser);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 7, 8192})
    void fieldNamesAreCaseInsensitiveAndOptionalWhitespaceIsRemoved(int readSize) throws Exception {
        Http1MessageParser parser = parser("GET / HTTP/1.1\r\nHost: localhost\r\nX-Example:\t first \t\r\nx-example: second\r\n\r\n", readSize);
        HttpRequestTemp request = (HttpRequestTemp) parser.readNext();
        assertEquals(List.of("first", "second"), request.headers().getAll("X-EXAMPLE"));
        assertSame(MessageBodyBit.EOFMsg, parser.readNext());
    }
}
