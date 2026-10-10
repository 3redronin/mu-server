package io.muserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.*;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

/** Real Mu request/response/lifecycle code, with a manually driven readiness transport. */
@Timeout(15)
class Http1BufferedTransportTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bodiesTrailersAndPipelinesSurviveBoundedFeedsAndPartialOutput(boolean chunked) throws Exception {
        List<String> handled = new ArrayList<>();
        try (var test = new Harness(200_000, (request, response) -> {
            String body = request.readBodyAsString();
            handled.add(body + "|" + request.trailers().get("checksum"));
            response.write(Integer.toString(body.length()));
            return true;
        })) {
            String body = "x".repeat(20_000);
            String framing = chunked ? "Transfer-Encoding: chunked\r\n" : "Content-Length: 20000\r\n";
            String payload = chunked ? "4e20\r\n" + body + "\r\n0\r\nChecksum: good\r\n\r\n" : body;
            String wire = "POST /one HTTP/1.1\r\nHost: localhost\r\n" + framing + "\r\n" + payload
                + "POST /two HTTP/1.1\r\nHost: localhost\r\nContent-Length: 3\r\nConnection: close\r\n\r\nend";
            test.drive(ByteBuffer.wrap(wire.getBytes(US_ASCII)), false);
            assertEquals(List.of(body + (chunked ? "|good" : "|null"), "end|null"), handled);
            assertEquals(2, test.connection.completedRequests());
            assertTrue(test.connection.activeRequests().isEmpty());
            var requests = new ArrayDeque<HttpRequestTemp>();
            for (int i = 0; i < 2; i++) {
                HttpRequestTemp request = HttpRequestTemp.empty();
                request.setMethod(Method.POST);
                requests.add(request);
            }
            var responses = new Http1MessageParser(HttpMessageType.RESPONSE, requests,
                new ByteArrayInputStream(test.sink.bytes.toByteArray()), 8192, 8192);
            for (String expected : List.of("20000", "3")) {
                assertEquals(200, ((HttpResponseTemp) responses.readNext()).getStatusCode());
                assertEquals(expected, new String(new Http1BodyStream(responses, 1000).readAllBytes(), US_ASCII));
            }
            assertTrue(MessageBodyBit.isEof(responses.readNext()));
            assertEquals(test.sink.bytes.size(), test.server.stats().bytesSent() - test.sentBefore);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void queuedResponseBytesDoNotAdvanceAccountingOrCompletion(boolean async) throws Exception {
        CompletableFuture<ResponseInfo> completed = new CompletableFuture<>();
        CompletableFuture<Future<?>> submitted = new CompletableFuture<>();
        try (var test = new Harness(1000, (request, response) -> {
            response.addCompletionListener(completed::complete);
            if (async) {
                AsyncHandle handle = request.handleAsync();
                submitted.complete(handle.write(ByteBuffer.wrap("hello".getBytes(US_ASCII))));
                handle.complete();
            } else response.write("hello");
            return true;
        })) {
            ByteBuffer request = ByteBuffer.wrap("GET /one HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".getBytes(US_ASCII));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (test.output.pendingBytes() == 0) {
                assertTrue(System.nanoTime() < deadline);
                if (request.hasRemaining()) test.input.offer(request);
                test.changed.tryAcquire(1, TimeUnit.MILLISECONDS);
            }
            assertEquals(test.sentBefore, test.server.stats().bytesSent());
            assertEquals(0, test.connection.completedRequests());
            assertFalse(completed.isDone());
            if (async) assertFalse(submitted.get(2, TimeUnit.SECONDS).isDone());
            test.drive(request, false);
            assertTrue(completed.get(2, TimeUnit.SECONDS).completedSuccessfully());
            if (async) submitted.get().get(2, TimeUnit.SECONDS);
            assertEquals(test.sink.bytes.size(), test.server.stats().bytesSent() - test.sentBefore);
        }
    }

    @Test
    void unreadChunkedBodyIsDiscardedBeforeTheNextPipelinedRequest() throws Exception {
        List<String> paths = new ArrayList<>();
        AtomicReference<MuRequest> first = new AtomicReference<>();
        try (var test = new Harness(200_000, (request, response) -> {
            paths.add(request.uri().getPath());
            if (request.uri().getPath().equals("/one")) first.set(request);
            response.write("ok");
            return true;
        })) {
            String request = "POST /one HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n"
                + "4e20\r\n" + "x".repeat(20_000) + "\r\n0\r\nChecksum: discarded\r\n\r\n"
                + "GET /two HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
            test.drive(ByteBuffer.wrap(request.getBytes(US_ASCII)), false);
            assertEquals(List.of("/one", "/two"), paths);
            assertEquals("discarded", first.get().trailers().get("checksum"));
            assertEquals(2, test.connection.completedRequests());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void completeHalfClosedBodiesFinishButTruncatedBodiesFail(boolean truncated) throws Exception {
        CompletableFuture<String> body = new CompletableFuture<>();
        try (var test = new Harness(1000, (request, response) -> {
            try { body.complete(request.readBodyAsString()); }
            catch (Exception failure) { body.completeExceptionally(failure); throw failure; }
            response.write("ok");
            return true;
        })) {
            String request = "POST /one HTTP/1.1\r\nHost: localhost\r\nContent-Length: 3\r\n\r\n"
                + (truncated ? "ab" : "abc");
            test.drive(ByteBuffer.wrap(request.getBytes(US_ASCII)), true);
            if (truncated) assertTrue(body.isCompletedExceptionally());
            else {
                assertEquals("abc", body.get(2, TimeUnit.SECONDS));
                assertTrue(test.sink.bytes.toString(US_ASCII).contains("ok"));
            }
        }
    }

    @Test
    void requestBodyLimitsStillRejectExternallyFedUploads() throws Exception {
        try (var test = new Harness(3, (request, response) -> {
            response.write(request.readBodyAsString());
            return true;
        })) {
            String request = "POST /one HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n"
                + "4\r\nlong\r\n0\r\n\r\n";
            test.drive(ByteBuffer.wrap(request.getBytes(US_ASCII)), false);
            assertTrue(test.sink.bytes.toString(US_ASCII).startsWith("HTTP/1.1 413 "));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void forcedCloseOrAsyncCancellationReleasesBlockedOutput(boolean async) throws Exception {
        CompletableFuture<ResponseInfo> completed = new CompletableFuture<>();
        CompletableFuture<Future<?>> submitted = new CompletableFuture<>();
        try (var test = new Harness(1000, (request, response) -> {
            response.addCompletionListener(completed::complete);
            if (async) {
                AsyncHandle handle = request.handleAsync();
                submitted.complete(handle.write(ByteBuffer.wrap(new byte[10000])));
                handle.complete();
            } else response.write("x".repeat(10000));
            return true;
        })) {
            ByteBuffer request = ByteBuffer.wrap("GET /one HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(US_ASCII));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (test.output.pendingBytes() == 0) {
                assertTrue(System.nanoTime() < deadline);
                if (request.hasRemaining()) test.input.offer(request);
                test.changed.tryAcquire(1, TimeUnit.MILLISECONDS);
            }
            if (async) assertTrue(submitted.get(2, TimeUnit.SECONDS).cancel(true));
            else test.connection.forceShutdown();
            test.running.get(2, TimeUnit.SECONDS);
            assertFalse(completed.get(2, TimeUnit.SECONDS).completedSuccessfully());
            assertEquals(test.sentBefore, test.server.stats().bytesSent());
        }
    }

    private static final class Harness implements AutoCloseable {
        final Semaphore changed = new Semaphore(0);
        final TransportInputBuffer input = new TransportInputBuffer(31, changed::release);
        final TransportOutputBuffer output = new TransportOutputBuffer(17, changed::release);
        final TransportOutputBufferTest.Sink sink = new TransportOutputBufferTest.Sink(3);
        final ExecutorService workers = Executors.newFixedThreadPool(2);
        final MuServer server;
        final Http1Connection connection;
        final Future<?> running;
        final long sentBefore;

        Harness(long maxBodySize, MuHandler handler) throws Exception {
            AtomicReference<BaseHttpConnection> fixture = new AtomicReference<>();
            CompletableFuture<Void> fixtureDone = new CompletableFuture<>();
            server = MuServerBuilder.httpServer().withMaxRequestSize(maxBodySize)
                .addHandler((request, response) -> {
                    if (request.uri().getPath().equals("/fixture")) {
                        fixture.set((BaseHttpConnection) request.connection());
                        response.addCompletionListener(info -> fixtureDone.complete(null));
                        response.write("ready");
                        return true;
                    }
                    return handler.handle(request, response);
                }).start();
            try (var response = call(request(server.uri().resolve("/fixture")))) {
                assertEquals("ready", response.body().string());
            }
            fixtureDone.get(2, TimeUnit.SECONDS);
            BaseHttpConnection config = fixture.get();
            connection = new Http1Connection(config.server, config.creator, new Controls(), ConnectionAcceptedTime.now(), null, workers);
            sentBefore = server.stats().bytesSent();
            running = workers.submit(() -> connection.start(new HttpConnectionInputStream(connection, input),
                new HttpConnectionOutputStream(connection, output)));
        }

        void drive(ByteBuffer request, boolean halfClose) throws Exception {
            boolean eof = false;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (!running.isDone()) {
                assertTrue(System.nanoTime() < deadline, "Connection failed to finish");
                int progress = 0;
                if (request.hasRemaining() && input.remainingCapacity() > 0) progress += input.offer(request);
                if (halfClose && !request.hasRemaining() && !eof) { input.endOfInput(); eof = true; }
                if (output.pendingBytes() > 0) progress += output.drainTo(sink, 5);
                assertTrue(input.available() <= 31);
                assertTrue(output.pendingBytes() <= 17);
                if (progress == 0) changed.tryAcquire(1, TimeUnit.MILLISECONDS);
            }
            running.get(2, TimeUnit.SECONDS);
        }

        @Override public void close() throws Exception {
            connection.forceShutdown();
            workers.shutdownNow();
            try { assertTrue(workers.awaitTermination(2, TimeUnit.SECONDS)); }
            finally { server.stop(); }
        }

        private final class Controls implements ConnectionTransport {
            @Override public InetSocketAddress remoteAddress() { return new InetSocketAddress("192.0.2.1", 1234); }
            @Override public InetSocketAddress localAddress() { return new InetSocketAddress("127.0.0.1", 80); }
            @Override public boolean isSecure() { return false; }
            @Override public String tlsProtocol() { return null; }
            @Override public String cipherSuite() { return null; }
            @Override public String sniHostName() { return null; }
            @Override public Certificate clientCertificate() { return null; }
            @Override public void readTimeoutMillis(int timeoutMillis) { input.readTimeoutMillis(timeoutMillis); }
            @Override public void shutdownInput() { input.endOfInput(); }
            @Override public void abort() {
                IOException cause = new IOException("Transport aborted");
                input.fail(cause);
                output.fail(cause);
            }
            @Override public void close() throws IOException {
                output.close();
                input.endOfInput();
            }
        }
    }
}
