package io.muserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class AsyncBodyReadDriverTest {
    @ParameterizedTest @ValueSource(strings = {"plain-fixed", "plain-chunked", "tls-fixed", "tls-chunked"})
    void stalledHttp1BodiesReleaseTheWorkerAndResumeThroughTheirExistingFraming(String mode) throws Exception {
        boolean chunked = mode.endsWith("chunked");
        var completed = new AtomicInteger();
        try (var fixture = new Fixture(mode.startsWith("tls"), (request, response) -> {
            response.status(204);
            if (request.method() == Method.POST) {
                var async = request.handleAsync();
                var received = new StringBuilder();
                async.setReadListener(new RequestBodyListener() {
                    @Override public void onDataReceived(ByteBuffer bytes, DoneCallback done) throws Exception {
                        assertTrue(Thread.currentThread().getName().startsWith("body-reader-application"));
                        received.append(StandardCharsets.US_ASCII.decode(bytes));
                        done.onComplete(null);
                    }
                    @Override public void onComplete() {
                        assertEquals("abc", received.toString());
                        if (chunked) assertEquals("yes", request.trailers().get("x-finished"));
                        completed.incrementAndGet();
                        async.complete();
                    }
                    @Override public void onError(Throwable error) { fail(error); }
                });
            }
            return true;
        })) {
            List<scaffolding.Http1Client> clients = new ArrayList<>();
            try {
                for (int i = 0; i < 8; i++) {
                    var client = fixture.connectHttp1();
                    clients.add(client);
                    client.writeRequestLine(Method.POST, "/").writeHeader("Connection", "close")
                        .writeHeader(chunked ? "Transfer-Encoding" : "Content-Length", chunked ? "chunked" : "3").flushHeaders();
                    fixture.timer.next();
                }
                fixture.barrier();
                try (var healthy = fixture.connectHttp1()) {
                    healthy.writeRequestLine(Method.GET, "/").writeHeader("Connection", "close").flushHeaders();
                    assertEquals("HTTP/1.1 204 No Content", healthy.readLine());
                    healthy.readHeaders();
                    assertEquals(-1, healthy.in().read());
                }
                for (var client : clients) client.writeAscii(chunked ? "1\r\na\r\n2\r\nbc\r\n0\r\nx-finished: yes\r\n\r\n" : "abc").flush();
                for (var client : clients) {
                    assertEquals("HTTP/1.1 204 No Content", client.readLine());
                    client.readHeaders();
                    assertEquals(-1, client.in().read());
                }
                assertEquals(8, completed.get());
            } finally { for (var client : clients) client.close(); }
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void stalledHttp2UploadsReleaseTheOnlyInternalWorkerAndCanBeCompletedEarly(boolean tls) throws Exception {
        List<AsyncHandle> handles = new CopyOnWriteArrayList<>();
        var callbacks = new AtomicInteger();
        try (var fixture = new Fixture(tls, (request, response) -> {
            response.status(204);
            if (request.method() == Method.POST) {
                var async = request.handleAsync();
                async.setReadListener(new RequestBodyListener() {
                    @Override public void onDataReceived(ByteBuffer bytes, DoneCallback done) { callbacks.incrementAndGet(); }
                    @Override public void onComplete() { callbacks.incrementAndGet(); }
                    @Override public void onError(Throwable error) { callbacks.incrementAndGet(); }
                });
                handles.add(async);
            }
            return true;
        }); var connection = fixture.connect()) {
            connection.handshake();
            for (int stream = 1; stream < 32; stream += 2) {
                connection.writeFrame(fixture.headers(stream, false, "POST"));
            }
            connection.flush();
            var deadlines = new ArrayList<Deadline>();
            for (int i = 0; i < 16; i++) deadlines.add(fixture.timer.next());
            fixture.barrier();
            assertEquals(16, handles.size());
            connection.writeFrame(fixture.headers(33, true, "GET")).flush();
            var healthy = RFCTestUtils.readIgnoringWindowUpdates(connection, Http2HeadersFrame.class);
            assertEquals(33, healthy.streamId());
            assertEquals("204", healthy.headers().get(":status"));
            for (AsyncHandle handle : handles) handle.complete();
            for (int i = 0; i < 16; i++) {
                var response = RFCTestUtils.readIgnoringWindowUpdates(connection, Http2HeadersFrame.class);
                assertTrue(response.streamId() < 33);
                assertTrue(response.endStream());
            }
            for (Deadline deadline : deadlines) {
                assertTrue(deadline.future.isCancelled());
                deadline.task.run(); // Already queued timeout notifications must be harmless after retirement.
            }
            for (int stream = 1; stream < 32; stream += 2) connection.writeFrame(Http2DataFrame.eos(stream));
            connection.flush();
            fixture.barrier();
            assertEquals(0, callbacks.get());
        }
    }

    @Test
    void dataCancelsItsDeadlineAndAnUnacknowledgedCallbackDoesNotStartAnother() throws Exception {
        var acknowledgement = new CompletableFuture<DoneCallback>();
        var failure = new CompletableFuture<Throwable>();
        var callbackThread = new CompletableFuture<String>();
        try (var fixture = new Fixture(false, (request, response) -> {
            if (request.method() == Method.GET) { response.status(204); return true; }
            var async = request.handleAsync();
            async.setReadListener(new RequestBodyListener() {
                @Override public void onDataReceived(ByteBuffer bytes, DoneCallback done) {
                    assertEquals(42, bytes.get()); acknowledgement.complete(done);
                }
                @Override public void onComplete() { fail("The peer has not finished its upload"); }
                @Override public void onError(Throwable error) {
                    callbackThread.complete(Thread.currentThread().getName()); failure.complete(error);
                }
            });
            return true;
        }); var connection = fixture.connect()) {
            connection.handshake().writeFrame(fixture.headers(1, false, "POST")).flush();
            Deadline first = fixture.timer.next();
            connection.writeFrame(new Http2DataFrame(1, false, new byte[]{42}, 0, 1)).flush();
            DoneCallback done = acknowledgement.get(3, TimeUnit.SECONDS);
            fixture.barrier();
            assertTrue(first.future.isCancelled());
            first.task.run();
            fixture.barrier();
            assertTrue(fixture.timer.deadlines.isEmpty(), "Application acknowledgement controls the next read");
            assertFalse(failure.isDone());
            done.onComplete(null);
            Deadline second = fixture.timer.next();
            connection.writeFrame(new Http2DataFrame(1, false, new byte[0], 0, 0)).flush();
            connection.writeFrame(new Http2Ping(false, new byte[8])).flush();
            assertTrue(RFCTestUtils.readIgnoringWindowUpdates(connection, Http2Ping.class).isAck());
            fixture.barrier();
            assertFalse(second.future.isCancelled(), "Empty DATA cannot extend a body read deadline");
            assertTrue(fixture.timer.deadlines.isEmpty());
            second.task.run();
            assertInstanceOf(HttpException.class, failure.get(3, TimeUnit.SECONDS));
            assertTrue(callbackThread.get().startsWith("body-reader-application"));
            var response = RFCTestUtils.readIgnoringWindowUpdates(connection, Http2HeadersFrame.class);
            assertEquals("408", response.headers().get(":status"));
            assertNull(response.headers().get(HeaderNames.CONNECTION));
            if (!response.endStream()) {
                while (!RFCTestUtils.readIgnoringWindowUpdates(connection, Http2DataFrame.class).endStream()) { }
            }
            connection.writeFrame(Http2DataFrame.eos(1)).writeFrame(fixture.headers(3, true, "GET")).flush();
            assertEquals(3, RFCTestUtils.readIgnoringWindowUpdates(connection, Http2HeadersFrame.class).streamId());
            done.onComplete(null); // A duplicate acknowledgement cannot restart the timed-out reader.
            fixture.barrier();
            assertTrue(fixture.timer.deadlines.isEmpty());
        }
    }

    @Test
    void aRejectedDeadlineFailsTheBodyInsteadOfStrandingItsReadinessWait() throws Exception {
        var failure = new CompletableFuture<Throwable>();
        try (var fixture = new Fixture(false, (request, response) -> {
            request.handleAsync().setReadListener(new RequestBodyListener() {
                @Override public void onDataReceived(ByteBuffer bytes, DoneCallback done) { fail("No data sent"); }
                @Override public void onComplete() { fail("No EOF sent"); }
                @Override public void onError(Throwable error) { failure.complete(error); }
            });
            return true;
        }); var connection = fixture.connect()) {
            fixture.timer.rejectBodyDeadline = true;
            connection.handshake().writeFrame(fixture.headers(1, false, "POST")).flush();
            assertInstanceOf(RejectedExecutionException.class, failure.get(3, TimeUnit.SECONDS));
            fixture.barrier();
            assertTrue(fixture.timer.deadlines.isEmpty());
        }
    }

    private static final long BODY_TIMEOUT = 47123;

    private static final class Fixture implements AutoCloseable {
        final ExecutorService internal = Executors.newSingleThreadExecutor(r -> new Thread(r, "body-reader-internal"));
        final ExecutorService application = Executors.newSingleThreadExecutor(r -> new Thread(r, "body-reader-application"));
        final Timer timer = new Timer();
        final MuServer server;
        final boolean tls;
        Fixture(boolean tls, MuHandler handler) {
            this.tls = tls;
            var builder = (tls ? MuServerBuilder.httpsServer() : MuServerBuilder.httpServer())
                .withHttp2Config(Http2ConfigBuilder.http2Enabled()).withHandlerExecutor(application)
                .withRequestTimeout(BODY_TIMEOUT, TimeUnit.MILLISECONDS).addHandler(handler);
            builder.useChannelTransport = true;
            builder.executionResourcesFactory = (supplied, mode) -> new ExecutionResources(application, false, internal, timer);
            server = builder.start();
        }
        H2ClientConnection connect() throws Exception {
            H2ClientConnection connection = tls ? new H2Client().connect(server) : new H2Client().connectClearText(server);
            connection.socket().setSoTimeout(3000);
            return connection;
        }
        scaffolding.Http1Client connectHttp1() throws Exception {
            Socket socket;
            if (tls) {
                var context = javax.net.ssl.SSLContext.getInstance("TLS");
                context.init(null, new javax.net.ssl.TrustManager[]{scaffolding.ClientUtils.veryTrustingTrustManager()}, null);
                socket = context.getSocketFactory().createSocket("localhost", server.uri().getPort());
            } else socket = new Socket("localhost", server.uri().getPort());
            socket.setSoTimeout(3000);
            return new scaffolding.Http1Client(socket, socket.getInputStream(), socket.getOutputStream(), server.uri());
        }
        Http2HeadersFrame headers(int stream, boolean end, String method) {
            var headers = RFCTestUtils.getHelloHeaders(tls ? "https" : "http", server.uri().getPort());
            headers.set(":method", method);
            return new Http2HeadersFrame(stream, end, headers);
        }
        void barrier() throws Exception {
            internal.submit(() -> {}).get(3, TimeUnit.SECONDS);
            application.submit(() -> {}).get(3, TimeUnit.SECONDS);
            internal.submit(() -> {}).get(3, TimeUnit.SECONDS);
        }
        @Override public void close() throws Exception {
            server.stop(0, TimeUnit.MILLISECONDS);
            internal.shutdownNow(); application.shutdownNow(); timer.shutdownNow();
            assertTrue(internal.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(application.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    private static final class Timer extends ScheduledThreadPoolExecutor {
        final BlockingQueue<Deadline> deadlines = new LinkedBlockingQueue<>();
        volatile boolean rejectBodyDeadline;
        Timer() { super(1); }
        @Override public ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
            if (unit.toMillis(delay) != BODY_TIMEOUT) return super.schedule(task, delay, unit);
            if (rejectBodyDeadline) throw new RejectedExecutionException("Injected body timer rejection");
            ScheduledFuture<?> future = super.schedule(task, 1, TimeUnit.DAYS);
            deadlines.add(new Deadline(task, future));
            return future;
        }
        Deadline next() throws Exception {
            Deadline deadline = deadlines.poll(3, TimeUnit.SECONDS);
            assertNotNull(deadline, "Body reader did not suspend with a deadline");
            return deadline;
        }
    }

    private static final class Deadline {
        final Runnable task;
        final ScheduledFuture<?> future;
        Deadline(Runnable task, ScheduledFuture<?> future) { this.task = task; this.future = future; }
    }
}
