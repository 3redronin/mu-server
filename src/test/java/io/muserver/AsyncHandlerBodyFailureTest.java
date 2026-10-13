package io.muserver;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class AsyncHandlerBodyFailureTest {
    @ParameterizedTest
    @CsvSource({"false,false,false", "false,true,false", "true,false,false", "true,true,false",
        "false,false,true", "false,true,true", "true,false,true", "true,true,true"})
    void handlerFailureRetainsAnExecutingBodyCallback(boolean channel, boolean h2, boolean fatalFailure) throws Exception {
        var acknowledgement = new CompletableFuture<DoneCallback>();
        var payload = new CompletableFuture<ByteBuffer>();
        var completed = new CompletableFuture<ResponseInfo>();
        var fatal = new CompletableFuture<Throwable>();
        var handlerReturned = new CompletableFuture<Void>();
        var handlerThread = new AtomicReference<Thread>();
        var releaseHandler = new CountDownLatch(1);
        var releaseCallback = new CountDownLatch(1);
        var readNotifications = new AtomicInteger();
        ThreadFactory threads = task -> {
            var thread = new Thread(task, "async-handler-failure");
            thread.setUncaughtExceptionHandler((worker, failure) -> fatal.complete(failure));
            return thread;
        };
        var application = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), threads) {
            @Override public void execute(Runnable task) {
                super.execute(() -> {
                    try { task.run(); }
                    finally {
                        if (Thread.currentThread().equals(handlerThread.get())) handlerReturned.complete(null);
                    }
                });
            }
        };
        var internal = Executors.newCachedThreadPool(threads);
        var timer = Executors.newSingleThreadScheduledExecutor();
        var builder = MuServerBuilder.httpServer().withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .withHandlerExecutor(application).withThreadingMode(ThreadingMode.PLATFORM)
            .withExceptionHandler((request, response, failure) -> { response.status(500); return true; });
        builder.useChannelTransport = channel;
        builder.executionResourcesFactory = (supplied, mode) -> new ExecutionResources(application, false, internal, timer);
        builder.addHandler((request, response) -> {
            handlerThread.set(Thread.currentThread());
            response.addCompletionListener(completed::complete);
            var async = request.handleAsync();
            async.setReadListener(new RequestBodyListener() {
                @Override public void onDataReceived(ByteBuffer bytes, DoneCallback done) throws Exception {
                    readNotifications.incrementAndGet();
                    payload.complete(bytes);
                    acknowledgement.complete(done);
                    releaseCallback.await();
                }
                @Override public void onComplete() { readNotifications.incrementAndGet(); }
                @Override public void onError(Throwable failure) { readNotifications.incrementAndGet(); }
            });
            releaseHandler.await();
            if (fatalFailure) throw new InternalError("Controlled async handler failure");
            throw new IOException("Controlled async handler failure");
        });
        MuServer server = builder.start();
        try (var h2Client = new H2Client();
             var socket = h2 ? null : new Socket("localhost", server.uri().getPort());
             var peer = h2 ? h2Client.connectClearText(server) : null) {
            if (peer != null) {
                var headers = RFCTestUtils.getHelloHeaders("http", server.uri().getPort());
                headers.set(":method", "POST"); headers.set("content-length", "1");
                peer.handshake().writeFrame(new Http2HeadersFrame(1, false, headers))
                    .writeFrame(new Http2DataFrame(1, true, new byte[]{42}, 0, 1)).flush();
            } else if (socket != null) {
                socket.getOutputStream().write("POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 1\r\nConnection: close\r\n\r\n*"
                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            }
            var done = acknowledgement.get(2, TimeUnit.SECONDS);
            releaseHandler.countDown();
            handlerReturned.get(2, TimeUnit.SECONDS);
            assertThrows(TimeoutException.class, () -> completed.get(100, TimeUnit.MILLISECONDS));
            assertEquals(0, server.stats().completedRequests(), "A callback still owns the body buffer");
            assertEquals((byte) 42, payload.get().get(0));
            releaseCallback.countDown();
            completed.get(3, TimeUnit.SECONDS);
            assertEquals(1, server.stats().completedRequests());
            if (fatalFailure) assertInstanceOf(InternalError.class, fatal.get(2, TimeUnit.SECONDS));
            done.onComplete(null);
            application.submit(() -> {}).get(2, TimeUnit.SECONDS);
            internal.submit(() -> {}).get(2, TimeUnit.SECONDS);
            assertEquals(1, readNotifications.get(), "Late acknowledgement cannot restart body delivery");
            assertEquals((byte) 42, payload.get().get(0));
        } finally {
            releaseHandler.countDown(); releaseCallback.countDown();
            server.stop(0, TimeUnit.MILLISECONDS);
            application.shutdownNow(); internal.shutdownNow(); timer.shutdownNow();
        }
    }
}
