package io.muserver;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** RFC 9112 section 6.3: response boundaries and request/response association. */
class Http1ResponseBoundaryTest {
    private static final String NEXT = "HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\nnext";

    static Stream<Arguments> bodylessResponses() {
        return IntStream.of(1, 2, 3, 7, 8192).boxed().flatMap(readSize -> Stream.of(
            Arguments.of(readSize, Method.HEAD, 200, ""),
            Arguments.of(readSize, Method.HEAD, 200, "Content-Length: 123\r\n"),
            Arguments.of(readSize, Method.HEAD, 404, "Transfer-Encoding: chunked\r\n"),
            Arguments.of(readSize, Method.GET, 204, ""),
            Arguments.of(readSize, Method.GET, 304, "Content-Length: 123\r\n"),
            Arguments.of(readSize, Method.GET, 304, "Transfer-Encoding: chunked\r\n")
        ));
    }

    @ParameterizedTest(name = "read={0}, method={1}, status={2}, headers={3}")
    @MethodSource("bodylessResponses")
    void bodylessFinalResponseStopsAtHeaders(int readSize, Method method, int status, String headers) throws Exception {
        HttpRequestTemp first = request(method);
        HttpRequestTemp second = request(Method.GET);
        Queue<HttpRequestTemp> requests = new ConcurrentLinkedQueue<>();
        requests.add(first);
        requests.add(second);
        Http1MessageParser parser = parser("HTTP/1.1 " + status + " Status\r\n" + headers + "\r\n" + NEXT, readSize, requests);

        HttpResponseTemp response = (HttpResponseTemp) parser.readNext();
        assertEquals(status, response.getStatusCode());
        assertSame(first, response.getRequest());
        assertEquals(BodySize.NONE, response.getBodySize());
        assertSame(second, requests.peek());
        assertNextResponse(parser, second, requests);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 7, 8192})
    void multipleInformationalResponsesKeepTheRequestUntilItsFinalResponse(int readSize) throws Exception {
        HttpRequestTemp head = request(Method.HEAD);
        HttpRequestTemp get = request(Method.GET);
        Queue<HttpRequestTemp> requests = new ConcurrentLinkedQueue<>();
        requests.add(head);
        requests.add(get);
        Http1MessageParser parser = parser("HTTP/1.1 100 Continue\r\n\r\n"
            + "HTTP/1.1 103 Early Hints\r\nLink: </style.css>; rel=preload\r\n\r\n"
            + "HTTP/1.1 102 Processing\r\n\r\n"
            + "HTTP/1.1 200 OK\r\nContent-Length: 999\r\n\r\n" + NEXT, readSize, requests);
        for (int status : new int[] {100, 103, 102}) {
            HttpResponseTemp informational = (HttpResponseTemp) parser.readNext();
            assertEquals(status, informational.getStatusCode());
            assertEquals(BodySize.NONE, informational.getBodySize());
            assertEquals(2, requests.size(), "An interim response must not consume the request");
            assertSame(head, requests.peek());
        }
        HttpResponseTemp finalHead = (HttpResponseTemp) parser.readNext();
        assertEquals(200, finalHead.getStatusCode());
        assertSame(head, finalHead.getRequest());
        assertEquals(BodySize.NONE, finalHead.getBodySize());
        assertNextResponse(parser, get, requests);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 7, 8192})
    void chunkedResponseTrailersDoNotConsumeTheFollowingResponse(int readSize) throws Exception {
        HttpRequestTemp first = request(Method.GET);
        HttpRequestTemp second = request(Method.GET);
        Queue<HttpRequestTemp> requests = new ConcurrentLinkedQueue<>();
        requests.add(first);
        requests.add(second);
        Http1MessageParser parser = parser("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
            + "3\r\none\r\n0\r\nX-Result: done\r\n\r\n" + NEXT, readSize, requests);
        HttpResponseTemp response = (HttpResponseTemp) parser.readNext();
        assertSame(first, response.getRequest());
        assertEquals(BodySize.CHUNKED, response.getBodySize());
        assertEquals("one", body(parser));
        assertEquals("done", parser.takeTrailers().get("x-result"));
        assertNull(response.headers().get("x-result"), "Trailers remain separate from headers");
        assertNull(parser.takeTrailers());
        assertNextResponse(parser, second, requests);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 7, 8192})
    void closeDelimitedResponseEndsOnlyAtEof(int readSize) throws Exception {
        Queue<HttpRequestTemp> requests = new ConcurrentLinkedQueue<>();
        HttpRequestTemp request = request(Method.GET);
        requests.add(request);
        Http1MessageParser parser = parser("HTTP/1.0 200 OK\r\n\r\nbody\r\n", readSize, requests);
        HttpResponseTemp response = (HttpResponseTemp) parser.readNext();
        assertSame(request, response.getRequest());
        assertEquals(BodySize.UNSPECIFIED, response.getBodySize());
        assertEquals("body\r\n", body(parser));
        assertSame(MessageBodyBit.EOFMsg, parser.readNext());
        assertTrue(requests.isEmpty());
    }

    private static HttpRequestTemp request(Method method) {
        HttpRequestTemp request = HttpRequestTemp.empty();
        request.setMethod(method);
        return request;
    }

    private static Http1MessageParser parser(String wire, int readSize, Queue<HttpRequestTemp> requests) {
        ByteArrayInputStream input = new ByteArrayInputStream(wire.getBytes(StandardCharsets.US_ASCII)) {
            @Override public synchronized int read(byte[] bytes, int offset, int length) {
                return super.read(bytes, offset, Math.min(readSize, length));
            }
        };
        return new Http1MessageParser(HttpMessageType.RESPONSE, requests, input, 8192, 8192);
    }

    private static void assertNextResponse(Http1MessageParser parser, HttpRequestTemp request, Queue<HttpRequestTemp> requests) throws Exception {
        HttpResponseTemp next = (HttpResponseTemp) parser.readNext();
        assertEquals(200, next.getStatusCode());
        assertSame(request, next.getRequest());
        assertEquals(BodyType.FIXED_SIZE, next.getBodySize().type());
        assertEquals(4L, next.getBodySize().size());
        assertEquals("next", body(parser));
        assertSame(MessageBodyBit.EOFMsg, parser.readNext());
        assertTrue(requests.isEmpty());
    }

    private static String body(Http1MessageParser parser) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < 32; i++) {
            Http1ConnectionMsg next = parser.readNext();
            assertTrue(next instanceof MessageBodyBit);
            assertNotSame(MessageBodyBit.EOFMsg, next, "Body must end before parser EOF");
            MessageBodyBit bit = (MessageBodyBit) next;
            bytes.write(bit.bytes(), bit.offset(), bit.length());
            if (bit.isLast()) return bytes.toString(StandardCharsets.US_ASCII);
        }
        throw new AssertionError("Body did not finish");
    }
}
