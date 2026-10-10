package io.muserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.*;

class Http1MessageDecoderTest {
    private static final String NEXT = "GET /next HTTP/1.1\r\nHost: localhost\r\n\r\n";
    private static final String FIXED = "POST /fixed HTTP/1.1\r\nHost: localhost\r\nContent-Length: 5\r\n\r\nhello";
    private static final String CHUNKED = "POST /chunked HTTP/1.1\r\nHost: localhost\r\n"
        + "Transfer-Encoding: chunked\r\n\r\n2;name=\"a\\\"b\"\r\nh\u00e9\r\n"
        + "3\r\nllo\r\n0\r\nX-Result: done\r\n\r\n";

    enum Storage { HEAP, SLICE, READ_ONLY, DIRECT }

    @ParameterizedTest
    @EnumSource(Storage.class)
    void everySplitPreservesRequestsBodiesAndTrailers(Storage storage) throws Exception {
        for (String first : List.of(NEXT, FIXED, CHUNKED)) {
            byte[] wire = bytes(first + NEXT);
            for (int split = 0; split <= wire.length; split++) {
                Http1MessageDecoder decoder = decoder(HttpMessageType.REQUEST, new ConcurrentLinkedQueue<>());
                Transcript transcript = new Transcript();
                drain(decoder, input(storage, wire, 0, split), transcript);
                // A pause between any two bytes must neither terminate nor poison the message.
                assertNull(decoder.decode(ByteBuffer.allocate(0)), "split=" + split);
                drain(decoder, input(storage, wire, split, wire.length - split), transcript);
                assertSame(MessageBodyBit.EOFMsg, decoder.endOfInput(), "split=" + split);
                assertEquals(List.of(first.equals(NEXT) ? "/next" : first.equals(FIXED) ? "/fixed" : "/chunked", "/next"),
                    transcript.targets, "split=" + split);
                assertEquals(List.of(first.equals(NEXT) ? "" : first.equals(FIXED) ? "hello" : "h\u00e9llo", ""),
                    transcript.bodies(), "split=" + split);
                assertEquals(first.equals(CHUNKED) ? List.of("done") : List.of(), transcript.trailers);
                for (HttpMessageTemp message : transcript.messages) {
                    assertEquals(HttpVersion.HTTP_1_1, message.getHttpVersion());
                    assertEquals("localhost", message.headers().get("host"));
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Storage.class)
    void oneByteAndRandomFeedsAgreeWithBlockingDriver(Storage storage) throws Exception {
        byte[] wire = bytes(FIXED + CHUNKED + NEXT);
        Http1MessageParser blocking = new Http1MessageParser(HttpMessageType.REQUEST,
            new ConcurrentLinkedQueue<>(), new ByteArrayInputStream(wire), 8192, 8192);
        Transcript expected = new Transcript();
        Http1ConnectionMsg event;
        while (!MessageBodyBit.isEof(event = blocking.readNext())) expected.accept(event, blocking.takeTrailers());

        for (int seed = 0; seed < 20; seed++) {
            Http1MessageDecoder decoder = decoder(HttpMessageType.REQUEST, new ConcurrentLinkedQueue<>());
            Transcript actual = new Transcript();
            Random random = new Random(seed);
            int offset = 0;
            while (offset < wire.length) {
                int length = Math.min(wire.length - offset, seed == 0 ? 1 : 1 + random.nextInt(31));
                drain(decoder, input(storage, wire, offset, length), actual);
                offset += length;
            }
            assertSame(MessageBodyBit.EOFMsg, decoder.endOfInput());
            assertEquals(expected.targets, actual.targets);
            assertEquals(expected.bodies(), actual.bodies());
            assertEquals(expected.trailers, actual.trailers);
        }
    }

    @ParameterizedTest
    @EnumSource(Storage.class)
    void responseAssociationSurvivesEverySplit(Storage storage) throws Exception {
        byte[] wire = bytes("HTTP/1.1 103 Early Hints\r\nLink: </style.css>\r\n\r\n"
            + "HTTP/1.1 200 OK\r\nContent-Length: 999\r\n\r\n"
            + "HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello");
        for (int split = 0; split <= wire.length; split++) {
            Queue<HttpRequestTemp> requests = new ConcurrentLinkedQueue<>();
            HttpRequestTemp head = HttpRequestTemp.empty();
            head.setMethod(Method.HEAD);
            HttpRequestTemp get = HttpRequestTemp.empty();
            get.setMethod(Method.GET);
            requests.add(head);
            requests.add(get);
            Http1MessageDecoder decoder = decoder(HttpMessageType.RESPONSE, requests);
            Transcript transcript = new Transcript();
            drain(decoder, input(storage, wire, 0, split), transcript);
            drain(decoder, input(storage, wire, split, wire.length - split), transcript);
            assertSame(MessageBodyBit.EOFMsg, decoder.endOfInput());
            assertEquals(List.of(103, 200, 200), transcript.statuses);
            assertEquals(List.of("", "", "hello"), transcript.bodies());
            assertSame(head, ((HttpResponseTemp) transcript.messages.get(1)).getRequest());
            assertSame(get, ((HttpResponseTemp) transcript.messages.get(2)).getRequest());
            assertTrue(requests.isEmpty());
        }
    }

    @Test
    void closeDelimitedBodyEndsOnlyOnExplicitEof() throws Exception {
        Queue<HttpRequestTemp> requests = new ConcurrentLinkedQueue<>();
        HttpRequestTemp request = HttpRequestTemp.empty();
        request.setMethod(Method.GET);
        requests.add(request);
        Http1MessageDecoder decoder = decoder(HttpMessageType.RESPONSE, requests);
        ByteBuffer input = ByteBuffer.wrap(bytes("HTTP/1.0 200 OK\r\n\r\nhello"));
        HttpResponseTemp response = assertInstanceOf(HttpResponseTemp.class, decoder.decode(input));
        assertEquals(BodySize.UNSPECIFIED, response.getBodySize());
        MessageBodyBit body = assertInstanceOf(MessageBodyBit.class, decoder.decode(input));
        assertFalse(body.isLast());
        assertEquals("hello", text(body));
        assertNull(decoder.decode(input));
        assertNull(decoder.decode(input));
        assertSame(MessageBodyBit.EndOfBodyBit, decoder.endOfInput());
        assertSame(MessageBodyBit.EOFMsg, decoder.endOfInput());
        assertSame(MessageBodyBit.EOFMsg, decoder.decode(ByteBuffer.allocate(0)));
        assertThrows(IllegalStateException.class, () -> decoder.decode(ByteBuffer.wrap(bytes("extra"))));
    }

    @Test
    void everyTruncatedPrefixFailsAtEofAndCannotResume() throws Exception {
        for (String complete : List.of(FIXED, CHUNKED)) {
            byte[] wire = bytes(complete);
            for (int size = 1; size < wire.length; size++) {
                Http1MessageDecoder decoder = decoder(HttpMessageType.REQUEST, new ConcurrentLinkedQueue<>());
                drain(decoder, ByteBuffer.wrap(wire, 0, size), new Transcript());
                assertThrows(ParseException.class, decoder::endOfInput, "prefix=" + size);
                assertNull(decoder.takeTrailers());
                assertThrows(ParseException.class, () -> decoder.decode(ByteBuffer.wrap(bytes(NEXT))));
                assertThrows(ParseException.class, decoder::endOfInput);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Storage.class)
    void malformedMessagesFailAcrossEverySplitAndCannotResume(Storage storage) {
        for (String malformed : List.of(
            "GET / HTTP/1.1\r\nBad Header: x\r\n\r\n",
            "POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n1;bad=\r\nx\r\n0\r\n\r\n",
            "POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n0\r\nContent-Length: 1\r\n\r\n")) {
            byte[] wire = bytes(malformed);
            Class<? extends Exception> error = malformed.contains("Content-Length") ? HttpException.class : ParseException.class;
            for (int split = 0; split <= wire.length; split++) {
                int boundary = split;
                Http1MessageDecoder decoder = decoder(HttpMessageType.REQUEST, new ConcurrentLinkedQueue<>());
                assertThrows(error, () -> {
                    drain(decoder, input(storage, wire, 0, boundary), new Transcript());
                    drain(decoder, input(storage, wire, boundary, wire.length - boundary), new Transcript());
                }, "split=" + split);
                assertNull(decoder.takeTrailers());
                assertThrows(ParseException.class, () -> decoder.decode(ByteBuffer.wrap(bytes(NEXT))));
            }
        }
    }

    @Test
    void bodyEventStopsBeforeNextRequestAndBorrowsTheCorrectArrayRange() throws Exception {
        byte[] wire = bytes(FIXED + NEXT);
        ByteBuffer input = input(Storage.SLICE, wire, 0, wire.length);
        Http1MessageDecoder decoder = decoder(HttpMessageType.REQUEST, new ConcurrentLinkedQueue<>());
        assertInstanceOf(HttpRequestTemp.class, decoder.decode(input));
        MessageBodyBit body = assertInstanceOf(MessageBodyBit.class, decoder.decode(input));
        assertSame(input.array(), body.bytes());
        assertEquals("hello", text(body));
        assertTrue(body.isLast());
        assertEquals(FIXED.length(), input.position());
        assertEquals(NEXT.length(), input.remaining());
        assertEquals("/next", assertInstanceOf(HttpRequestTemp.class, decoder.decode(input)).getUrl());
    }

    @ParameterizedTest
    @EnumSource(value = Storage.class, names = {"DIRECT", "READ_ONLY"})
    void bodyFromInaccessibleBackingArrayOwnsItsCopy(Storage storage) throws Exception {
        byte[] wire = bytes(FIXED);
        ByteBuffer input = input(storage, wire, 0, wire.length);
        Http1MessageDecoder decoder = decoder(HttpMessageType.REQUEST, new ConcurrentLinkedQueue<>());
        assertInstanceOf(HttpRequestTemp.class, decoder.decode(input));
        MessageBodyBit body = assertInstanceOf(MessageBodyBit.class, decoder.decode(input));
        assertEquals("hello", text(body));
        if (storage == Storage.DIRECT) {
            input.clear();
            while (input.hasRemaining()) input.put((byte) 'x');
        }
        assertEquals("hello", text(body));
        assertEquals(0, body.offset());
        assertEquals(5, body.bytes().length);
    }

    @Test
    void websocketHeaderEventLeavesTakeoverBytesUntouched() throws Exception {
        String headers = "GET /socket HTTP/1.1\r\nHost: localhost\r\nConnection: Upgrade\r\n"
            + "Upgrade: websocket\r\n\r\n";
        byte[] frame = {(byte) 0x81, (byte) 0x80, 1, 2, 3, 4};
        ByteBuffer input = ByteBuffer.allocate(bytes(headers).length + frame.length);
        input.put(bytes(headers)).put(frame).flip();
        Http1MessageDecoder decoder = decoder(HttpMessageType.REQUEST, new ConcurrentLinkedQueue<>());
        assertTrue(assertInstanceOf(HttpRequestTemp.class, decoder.decode(input)).isWebsocketUpgrade());
        byte[] remaining = new byte[input.remaining()];
        input.get(remaining);
        assertArrayEquals(frame, remaining);
    }

    @Test
    void transportReadFailureMakesBlockingDriverTerminal() throws Exception {
        InputStream input = new InputStream() {
            @Override public int read() throws IOException { throw new IOException("disconnected"); }
        };
        Http1MessageParser parser = new Http1MessageParser(HttpMessageType.REQUEST,
            new ConcurrentLinkedQueue<>(), input, 8192, 8192);
        assertThrows(IOException.class, parser::readNext);
        assertThrows(ParseException.class, parser::readNext);
    }

    @Test
    void uncheckedTransportFailureAlsoMakesBlockingDriverTerminal() {
        for (RuntimeException failure : List.of(new IllegalArgumentException("broken input"),
            HttpException.badRequest("broken input"))) {
            InputStream input = new InputStream() {
                private boolean failed;
                @Override public int read() {
                    if (failed) throw new AssertionError("A failed source must not be read again");
                    failed = true;
                    throw failure;
                }
            };
            Http1MessageParser parser = new Http1MessageParser(HttpMessageType.REQUEST,
                new ConcurrentLinkedQueue<>(), input, 8192, 8192);
            assertSame(failure, assertThrows(failure.getClass(), parser::readNext));
            assertThrows(ParseException.class, parser::readNext);
        }
    }

    private static Http1MessageDecoder decoder(HttpMessageType type, Queue<HttpRequestTemp> requests) {
        return new Http1MessageDecoder(type, requests, 8192, 8192);
    }

    private static byte[] bytes(String wire) { return wire.getBytes(StandardCharsets.ISO_8859_1); }

    private static String text(MessageBodyBit body) {
        return new String(body.bytes(), body.offset(), body.length(), StandardCharsets.ISO_8859_1);
    }

    /** Leave padding before/after input to detect accidental reads outside position/limit. */
    private static ByteBuffer input(Storage storage, byte[] bytes, int offset, int length) {
        ByteBuffer input = storage == Storage.DIRECT ? ByteBuffer.allocateDirect(length + 5) : ByteBuffer.allocate(length + 5);
        input.position(2).put(bytes, offset, length).flip().position(2);
        if (storage == Storage.SLICE) return input.slice();
        if (storage == Storage.READ_ONLY) return input.asReadOnlyBuffer();
        return input;
    }

    private static void drain(Http1MessageDecoder decoder, ByteBuffer input, Transcript transcript) throws Exception {
        Http1ConnectionMsg event;
        while ((event = decoder.decode(input)) != null) transcript.accept(event, decoder.takeTrailers());
        assertFalse(input.hasRemaining());
    }

    private static final class Transcript {
        final List<HttpMessageTemp> messages = new ArrayList<>();
        final List<String> targets = new ArrayList<>();
        final List<Integer> statuses = new ArrayList<>();
        final List<ByteArrayOutputStream> payloads = new ArrayList<>();
        final List<String> trailers = new ArrayList<>();

        void accept(Http1ConnectionMsg event, FieldBlock fields) {
            if (event instanceof HttpMessageTemp) {
                messages.add((HttpMessageTemp) event);
                payloads.add(new ByteArrayOutputStream());
                if (event instanceof HttpRequestTemp) targets.add(((HttpRequestTemp) event).getUrl());
                else statuses.add(((HttpResponseTemp) event).getStatusCode());
            } else if (MessageBodyBit.isEndOfBody(event)) {
                if (fields != null) trailers.add(fields.get("x-result"));
            } else {
                MessageBodyBit body = (MessageBodyBit) event;
                payloads.get(payloads.size() - 1).write(body.bytes(), body.offset(), body.length());
            }
        }

        List<String> bodies() {
            List<String> result = new ArrayList<>();
            for (ByteArrayOutputStream bytes : payloads) result.add(bytes.toString(StandardCharsets.ISO_8859_1));
            return result;
        }
    }
}
