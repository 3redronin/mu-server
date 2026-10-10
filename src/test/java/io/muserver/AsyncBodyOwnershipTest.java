package io.muserver;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import scaffolding.Http1Client;

import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class AsyncBodyOwnershipTest {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void completingAnHttp2ResponseWakesItsBodyReaderAndDiscardsTheRemainingUpload(boolean channel) throws Exception {
        var handle = new CompletableFuture<AsyncHandle>();
        var notifications = new AtomicInteger();
        var application = Executors.newSingleThreadExecutor();
        var builder = MuServerBuilder.httpServer().withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .withHandlerExecutor(application);
        builder.useChannelTransport = channel;
        builder.addHandler((request, response) -> {
            response.status(204);
            if (request.method() == Method.POST) {
                var async = request.handleAsync();
                async.setReadListener(new RequestBodyListener() {
                    @Override public void onDataReceived(ByteBuffer bytes, DoneCallback done) throws Exception {
                        notifications.incrementAndGet(); done.onComplete(null);
                    }
                    @Override public void onComplete() { notifications.incrementAndGet(); }
                    @Override public void onError(Throwable failure) { notifications.incrementAndGet(); }
                });
                handle.complete(async);
            }
            return true;
        });
        try (MuServer server = builder.start(); var client = new H2Client(); var connection = client.connectClearText(server)) {
            connection.socket().setSoTimeout(2000);
            var headers = RFCTestUtils.getHelloHeaders("http", server.uri().getPort());
            headers.set(":method", "POST");
            headers.set("content-length", "3");
            connection.handshake().writeFrame(new Http2HeadersFrame(1, false, headers)).flush();
            handle.get(2, TimeUnit.SECONDS).complete();
            var response = RFCTestUtils.readIgnoringWindowUpdates(connection, Http2HeadersFrame.class);
            assertEquals(1, response.streamId());
            assertEquals("204", response.headers().get(":status"));
            assertTrue(response.endStream());
            assertEquals(1, server.stats().activeRequests().size(), "An unfinished upload still owns its protocol slot");
            connection.writeFrame(new Http2DataFrame(1, true, new byte[]{1, 2, 3}, 0, 3))
                .writeFrame(new Http2HeadersFrame(3, true, RFCTestUtils.getHelloHeaders("http", server.uri().getPort()))).flush();
            assertEquals(3, RFCTestUtils.readIgnoringWindowUpdates(connection, Http2HeadersFrame.class).streamId());
            until(() -> server.stats().completedRequests() == 2);
            application.submit(() -> {}).get(2, TimeUnit.SECONDS);
            assertEquals(0, notifications.get());
        } finally { application.shutdownNow(); }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void rejectedBodyErrorNotificationStillCompletesTheExchange(boolean channel) throws Exception {
        var handle = new CompletableFuture<AsyncHandle>();
        var application = Executors.newSingleThreadExecutor();
        var builder = MuServerBuilder.httpServer().withHandlerExecutor(application);
        builder.useChannelTransport = channel;
        builder.addHandler((request, response) -> {
            var async = request.handleAsync();
            async.setReadListener(new RequestBodyListener() {
                @Override public void onDataReceived(ByteBuffer bytes, DoneCallback done) { fail("No body sent"); }
                @Override public void onComplete() { fail("Body is incomplete"); }
                @Override public void onError(Throwable failure) { fail("Callback should be rejected"); }
            });
            handle.complete(async);
            return true;
        });
        try (MuServer server = builder.start(); Socket socket = new Socket("localhost", server.uri().getPort());
             var client = new Http1Client(socket, socket.getInputStream(), socket.getOutputStream(), server.uri())) {
            client.writeRequestLine(Method.POST, "/").writeHeader("Content-Length", 3).flushHeaders();
            handle.get(2, TimeUnit.SECONDS);
            application.shutdown();
            assertTrue(application.awaitTermination(2, TimeUnit.SECONDS));
            socket.shutdownOutput();
            until(() -> server.stats().completedRequests() == 1 && server.stats().activeConnections() == 0);
        } finally { application.shutdownNow(); }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void completingWhileABodyReadWaitsHandsTheParserToCleanupBeforeTheNextRequest(boolean channel) throws Exception {
        List<Thread> workers = new CopyOnWriteArrayList<>();
        var internal = Executors.newCachedThreadPool(task -> {
            var thread = new Thread(task, "body-ownership-internal-" + workers.size());
            workers.add(thread);
            return thread;
        });
        var application = Executors.newSingleThreadExecutor();
        var waiting = new CompletableFuture<Void>();
        var timer = new java.util.concurrent.ScheduledThreadPoolExecutor(1) {
            @Override public java.util.concurrent.ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
                if (unit.toMillis(delay) == 12345) waiting.complete(null);
                return super.schedule(task, delay, unit);
            }
        };
        var handle = new CompletableFuture<AsyncHandle>();
        var deliveries = new AtomicInteger();
        var builder = MuServerBuilder.httpServer().withHandlerExecutor(application).withRequestTimeout(12345, TimeUnit.MILLISECONDS);
        builder.useChannelTransport = channel;
        builder.executionResourcesFactory = (supplied, mode) -> new ExecutionResources(application, false, internal, timer);
        builder.addHandler((request, response) -> {
            if (request.uri().getPath().equals("/second")) {
                response.headers().set(HeaderNames.CONTENT_LENGTH, 6);
                response.write("second");
            } else {
                var async = request.handleAsync();
                async.setReadListener(new RequestBodyListener() {
                    @Override public void onDataReceived(ByteBuffer bytes, DoneCallback done) throws Exception {
                        deliveries.incrementAndGet(); done.onComplete(null);
                    }
                    @Override public void onComplete() { deliveries.incrementAndGet(); }
                    @Override public void onError(Throwable failure) { deliveries.incrementAndGet(); }
                });
                handle.complete(async);
            }
            return true;
        });
        try (MuServer server = builder.start(); Socket socket = new Socket("localhost", server.uri().getPort());
             var client = new Http1Client(socket, socket.getInputStream(), socket.getOutputStream(), server.uri())) {
            client.writeRequestLine(Method.POST, "/first").writeHeader("Content-Length", 3).flushHeaders();
            AsyncHandle async = handle.get(2, TimeUnit.SECONDS);
            // Establish the offending interleaving: the reader already owns parser storage and
            // is waiting for input when response completion asks cleanup to discard the body.
            if (channel) waiting.get(2, TimeUnit.SECONDS);
            else until(() -> workers.stream().anyMatch(AsyncBodyOwnershipTest::readingBody));
            async.complete();
            socket.setSoTimeout(100);
            assertThrows(SocketTimeoutException.class, () -> client.in().read(), "Cleanup must await the real request body");
            socket.setSoTimeout(2000);
            client.writeAscii("abcGET /second HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n").flush();
            assertEquals("HTTP/1.1 200 OK", client.readLine());
            assertEquals("", client.readBody(client.readHeaders()));
            assertEquals("HTTP/1.1 200 OK", client.readLine());
            assertEquals("second", client.readBody(client.readHeaders()));
            assertEquals(-1, client.in().read());
            until(() -> server.stats().completedRequests() == 2);
            assertEquals(0, deliveries.get(), "Completion cancels further body delivery and late acknowledgements");
        } finally { internal.shutdownNow(); application.shutdownNow(); timer.shutdownNow(); }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void completionRetainsAnExecutingBodyCallbackAndIgnoresItsLateAcknowledgement(boolean channel) throws Exception {
        var handle = new CompletableFuture<AsyncHandle>();
        var acknowledgement = new CompletableFuture<DoneCallback>();
        var payload = new CompletableFuture<ByteBuffer>();
        var releaseCallback = new CountDownLatch(1);
        var completions = new CompletableFuture<Void>();
        var builder = MuServerBuilder.httpServer();
        builder.useChannelTransport = channel;
        builder.addHandler((request, response) -> {
            var async = request.handleAsync();
            async.setReadListener(new RequestBodyListener() {
                @Override public void onDataReceived(ByteBuffer bytes, DoneCallback done) throws Exception {
                    payload.complete(bytes);
                    acknowledgement.complete(done);
                    releaseCallback.await();
                }
                @Override public void onComplete() { completions.complete(null); }
                @Override public void onError(Throwable failure) { completions.completeExceptionally(failure); }
            });
            handle.complete(async);
            return true;
        });
        try (MuServer server = builder.start(); Socket socket = new Socket("localhost", server.uri().getPort());
             var client = new Http1Client(socket, socket.getInputStream(), socket.getOutputStream(), server.uri())) {
            try {
                client.writeRequestLine(Method.POST, "/").writeHeader("Content-Length", 1)
                    .writeHeader("Connection", "close").endHeaders().writeAscii("a").flush();
                var done = acknowledgement.get(2, TimeUnit.SECONDS);
                handle.get().complete();
                socket.setSoTimeout(100);
                assertThrows(SocketTimeoutException.class, () -> client.in().read());
                assertEquals(0, server.stats().completedRequests());
                assertEquals((byte) 'a', payload.get().get(0));
                releaseCallback.countDown();
                socket.setSoTimeout(2000);
                assertEquals("HTTP/1.1 200 OK", client.readLine());
                assertEquals("", client.readBody(client.readHeaders()));
                assertEquals(-1, client.in().read());
                done.onComplete(null);
                assertThrows(TimeoutException.class, () -> completions.get(100, TimeUnit.MILLISECONDS));
                assertEquals((byte) 'a', payload.get().get(0));
            } finally { releaseCallback.countDown(); }
        }
    }

    private static boolean readingBody(Thread thread) {
        for (var frame : thread.getStackTrace()) {
            if (frame.getClassName().equals("io.muserver.Http1BodyStream") && frame.getMethodName().equals("blockUntilData")) return true;
        }
        return false;
    }

    private static void until(BooleanSupplier done) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!done.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "Body ownership did not progress");
            Thread.sleep(1);
        }
    }
}
