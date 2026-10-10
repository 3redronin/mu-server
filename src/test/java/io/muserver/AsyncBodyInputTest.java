package io.muserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class AsyncBodyInputTest {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void pollingDiscardPreservesPartiallyConsumedDataAndEveryFramingBoundary(boolean chunked) throws Exception {
        var source = new TransportInputBuffer(256, () -> {});
        var parser = pollingParser(source);
        String header = "POST / HTTP/1.1\r\nHost: localhost\r\n" +
            (chunked ? "Transfer-Encoding: chunked\r\n" : "Content-Length: 5\r\n") + "\r\n";
        source.offer(ByteBuffer.wrap((header + (chunked ? "3\r\nabc" : "abc")).getBytes(StandardCharsets.US_ASCII)));
        assertInstanceOf(HttpRequestTemp.class, parser.readAvailable(source::readAvailable));
        var body = new Http1BodyStream(parser, 5);
        assertEquals(1, body.asynchronousInput().readAvailable(new byte[1]));
        assertEquals(Http1BodyStream.State.DISCARDING, body.discardAvailable(true));
        assertEquals(0, body.available());
        assertThrows(IOException.class, body::read);
        String remaining = chunked ? "\r\n2\r\nde\r\n0\r\nx-check: yes\r\n\r\n" : "de";
        for (int i = 0; i < remaining.length(); i++) {
            source.offer(ByteBuffer.wrap(new byte[]{(byte) remaining.charAt(i)}));
            assertEquals(i == remaining.length() - 1 ? Http1BodyStream.State.EOF : Http1BodyStream.State.DISCARDING,
                body.discardAvailable(true));
        }
        assertEquals(5, body.bytesReceived());
        assertTrue(body.isRequestBodyComplete());
        if (chunked) assertEquals("yes", body.trailers().get("x-check"));
        source.offer(ByteBuffer.wrap("GET /next HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(Http1BodyStream.State.EOF, body.discardAvailable(true));
        assertInstanceOf(HttpRequestTemp.class, parser.readAvailable(source::readAvailable));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void pollingHttp1RetainsBodyAndTrailerStateAcrossEveryInputSplit(boolean chunked) throws Exception {
        String encoded = chunked ? "3\r\nabc\r\n2\r\nde\r\n0\r\nx-check: yes\r\n\r\n" : "abcde";
        byte[] bytes = encoded.getBytes(StandardCharsets.US_ASCII);
        for (int split = 0; split <= bytes.length; split++) {
            var source = new TransportInputBuffer(128, () -> {});
            source.readTimeoutMillis(42);
            var parser = pollingParser(source);
            String header = "POST / HTTP/1.1\r\nHost: localhost\r\n" +
                (chunked ? "Transfer-Encoding: chunked\r\n" : "Content-Length: 5\r\n") + "\r\n";
            source.offer(ByteBuffer.wrap(header.getBytes(StandardCharsets.US_ASCII)));
            assertInstanceOf(HttpRequestTemp.class, parser.readAvailable(source::readAvailable));
            var body = new Http1BodyStream(parser, 5);
            var polling = body.asynchronousInput();
            assertNotNull(polling);
            var received = new ByteArrayOutputStream();
            source.offer(ByteBuffer.wrap(bytes, 0, split));
            byte[] output = new byte[2];
            int count;
            while ((count = polling.readAvailable(output)) > 0) received.write(output, 0, count);
            if (split < bytes.length) {
                assertEquals(0, count);
                var waiting = polling.whenReadable();
                assertFalse(waiting.isDone());
                source.offer(ByteBuffer.wrap(bytes, split, bytes.length - split));
                waiting.get(1, TimeUnit.SECONDS);
            }
            while ((count = polling.readAvailable(output)) > 0) received.write(output, 0, count);
            assertEquals(-1, count);
            assertEquals("abcde", received.toString(StandardCharsets.US_ASCII));
            assertEquals(5, body.bytesReceived());
            assertTrue(body.isRequestBodyComplete());
            if (chunked) assertEquals("yes", body.trailers().get("x-check"));
            assertEquals(42, polling.readTimeoutMillis());
            source.offer(ByteBuffer.wrap("GET /next HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII)));
            var next = (HttpRequestTemp) parser.readAvailable(source::readAvailable);
            assertNotNull(next);
            assertEquals(Method.GET, next.getMethod());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"timeout", "incomplete", "oversized", "malformed"})
    void pollingHttp1KeepsFailuresTerminal(String failure) throws Exception {
        var source = new TransportInputBuffer(256, () -> {});
        var parser = pollingParser(source);
        source.offer(ByteBuffer.wrap("POST / HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n".getBytes(StandardCharsets.US_ASCII)));
        assertInstanceOf(HttpRequestTemp.class, parser.readAvailable(source::readAvailable));
        var body = new Http1BodyStream(parser, 1);
        var input = body.asynchronousInput();
        assertNotNull(input);
        byte[] output = new byte[8];
        assertEquals(0, input.readAvailable(output));
        switch (failure) {
            case "timeout":
                HttpException timeout = (HttpException) input.timeoutFailure();
                assertEquals(HttpStatus.REQUEST_TIMEOUT_408, timeout.status());
                assertEquals("close", timeout.responseHeaders().get(HeaderNames.CONNECTION));
                assertThrows(HttpException.class, () -> input.readAvailable(output));
                assertEquals(Http1BodyStream.State.TIMED_OUT, body.state());
                break;
            case "incomplete":
                source.endOfInput();
                assertThrows(IOException.class, () -> input.readAvailable(output));
                assertEquals(Http1BodyStream.State.IO_EXCEPTION, body.state());
                break;
            case "oversized":
                source.offer(ByteBuffer.wrap("2\r\nab\r\n".getBytes(StandardCharsets.US_ASCII)));
                assertEquals(HttpStatus.CONTENT_TOO_LARGE_413,
                    assertThrows(HttpException.class, () -> input.readAvailable(output)).status());
                assertThrows(HttpException.class, () -> input.readAvailable(output));
                assertTrue(body.tooBig());
                break;
            case "malformed":
                source.offer(ByteBuffer.wrap("zz\r\n".getBytes(StandardCharsets.US_ASCII)));
                assertThrows(Exception.class, () -> input.readAvailable(output));
                assertEquals(Http1BodyStream.State.IO_EXCEPTION, body.state());
                break;
            default: throw new AssertionError(failure);
        }
        assertFalse(body.isRequestBodyComplete());
    }

    private static Http1MessageParser pollingParser(TransportInputBuffer source) {
        return new Http1MessageParser(HttpMessageType.REQUEST, new ArrayDeque<>(), new InputStream() {
            @Override public int read() { throw new AssertionError("A polling body performed a blocking source read"); }
        }, 8192, 8192, source, source::readAvailable);
    }

    @Test
    void transportReadinessDistinguishesEmptyInputEofAndFailureAndCanReplaceACancelledWait() throws Exception {
        var notifications = new AtomicInteger();
        var input = new TransportInputBuffer(3, () -> {}, notifications::incrementAndGet);
        byte[] bytes = new byte[3];
        assertEquals(0, input.readAvailable(bytes));
        var cancelled = input.whenReadable();
        assertFalse(cancelled.isDone());
        assertTrue(cancelled.cancel(false));
        var ready = input.whenReadable();
        assertNotSame(cancelled, ready);
        input.offer(ByteBuffer.wrap(new byte[]{1, 2, 3}));
        ready.get(1, TimeUnit.SECONDS);
        assertTrue(input.whenReadable().isDone());
        assertEquals(3, input.readAvailable(bytes));
        assertArrayEquals(new byte[]{1, 2, 3}, bytes);
        var end = input.whenReadable();
        assertFalse(end.isDone());
        input.endOfInput();
        end.get(1, TimeUnit.SECONDS);
        assertEquals(-1, input.readAvailable(bytes));
        assertTrue(input.whenReadable().isDone());
        IOException failed = new IOException("Failure wins over EOF");
        input.fail(failed);
        assertTrue(input.whenReadable().isDone());
        assertSame(failed, assertThrows(IOException.class, () -> input.readAvailable(bytes)));
        assertEquals(3, notifications.get(), "The original readiness owner still receives its notifications");
    }

    @Test
    void pollingHttp2PreservesPartialDataPaddingCreditAndTrailers() throws Exception {
        var credit = new AtomicInteger();
        var body = new Http2BodyInputStream(37, credit::addAndGet, ignored -> {});
        byte[] bytes = new byte[2];
        assertEquals(0, body.readAvailable(bytes));
        var first = body.whenReadable();
        body.onData(new Http2DataFrame(1, false, new byte[]{1, 2, 3}, 0, 3), 6);
        first.get(1, TimeUnit.SECONDS);
        assertEquals(2, body.readAvailable(bytes));
        assertArrayEquals(new byte[]{1, 2}, bytes);
        assertEquals(2, credit.get());
        assertTrue(body.whenReadable().isDone());
        assertEquals(1, body.readAvailable(bytes));
        assertEquals(3, bytes[0]);
        assertEquals(6, credit.get());
        assertEquals(0, body.readAvailable(bytes));
        var trailerWait = body.whenReadable();
        body.onData(new Http2DataFrame(1, false, new byte[0], 0, 0), 4);
        assertEquals(10, credit.get());
        assertFalse(trailerWait.isDone(), "Padding without END_STREAM must not reset a request-body deadline");
        var trailers = new FieldBlock();
        trailers.set("x-trailer", "done");
        body.onTrailers(trailers);
        trailerWait.get(1, TimeUnit.SECONDS);
        assertEquals(-1, body.readAvailable(bytes));
        assertTrue(body.isRequestBodyComplete());
        assertEquals("done", body.trailers().get("x-trailer"));
        assertEquals(37, body.readTimeoutMillis());
        var timeout = (HttpException) body.timeoutFailure();
        assertEquals(HttpStatus.REQUEST_TIMEOUT_408, timeout.status());
        assertNull(timeout.responseHeaders().get(HeaderNames.CONNECTION));
    }

    @ParameterizedTest @ValueSource(strings = {"cancel", "reset", "discard", "end"})
    void everyHttp2TerminalInputWakesTheWaiter(String ending) throws Exception {
        var body = new Http2BodyInputStream(0, ignored -> {}, ignored -> {});
        var first = body.whenReadable();
        first.cancel(false);
        var ready = body.whenReadable();
        assertFalse(ready.isDone());
        IOException cancelled = new IOException("cancelled");
        switch (ending) {
            case "cancel": body.cancel(cancelled); break;
            case "reset": body.onStreamReset(new Http2ResetStreamFrame(1, Http2ErrorCode.CANCEL.code())); break;
            case "discard": body.discardRemaining(); break;
            case "end": body.onData(new Http2DataFrame(1, true, new byte[0], 0, 0)); break;
            default: throw new AssertionError(ending);
        }
        ready.get(1, TimeUnit.SECONDS);
        assertTrue(body.whenReadable().isDone());
        byte[] bytes = new byte[1];
        if (ending.equals("cancel")) assertSame(cancelled, assertThrows(IOException.class, () -> body.readAvailable(bytes)));
        else if (ending.equals("reset")) assertThrows(IOException.class, () -> body.readAvailable(bytes));
        else assertEquals(-1, body.readAvailable(bytes));
    }

    @Test
    void readinessFutureListenersRunOutsideBothInputStorageLocks() throws Exception {
        ExecutorService observer = Executors.newSingleThreadExecutor();
        try {
            var input = new TransportInputBuffer(3, () -> {});
            var inputNotified = input.whenReadable().thenRun(() -> {
                try { assertEquals(1, observer.submit(input::available).get(1, TimeUnit.SECONDS)); }
                catch (Exception failure) { throw new AssertionError("Transport lock held during notification", failure); }
            });
            input.offer(ByteBuffer.wrap(new byte[]{1}));
            inputNotified.get(2, TimeUnit.SECONDS);
            var body = new Http2BodyInputStream(0, ignored -> {}, ignored -> {});
            var bodyNotified = body.whenReadable().thenRun(() -> {
                try { assertTrue(observer.submit(body::isRequestBodyComplete).get(1, TimeUnit.SECONDS)); }
                catch (Exception failure) { throw new AssertionError("Body queue lock held during notification", failure); }
            });
            body.onTrailers(new FieldBlock());
            bodyNotified.get(2, TimeUnit.SECONDS);
        } finally { observer.shutdownNow(); }
    }

    @Test
    void readinessRegistrationCannotLoseAnOfferRacingWithTheEmptyCheck() throws Exception {
        var producer = Executors.newSingleThreadExecutor();
        try {
            var input = new TransportInputBuffer(1, () -> {});
            var body = new Http2BodyInputStream(0, ignored -> {}, ignored -> {});
            byte[] buffer = new byte[1];
            for (int i = 0; i < 500; i++) {
                var offered = producer.submit(() -> {
                    input.offer(ByteBuffer.wrap(new byte[]{42}));
                    body.onData(new Http2DataFrame(1, false, new byte[]{42}, 0, 1));
                    return null;
                });
                CompletableFuture.allOf(input.whenReadable(), body.whenReadable()).get(1, TimeUnit.SECONDS);
                offered.get(1, TimeUnit.SECONDS);
                assertEquals(1, input.readAvailable(buffer));
                assertEquals(42, buffer[0]);
                assertEquals(1, body.readAvailable(buffer));
                assertEquals(42, buffer[0]);
            }
        } finally { producer.shutdownNow(); }
    }
}
