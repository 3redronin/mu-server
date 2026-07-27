package io.muserver;

import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.muserver.MuServerBuilder.httpServer;
import static io.muserver.WebSocketHandlerBuilder.webSocketHandler;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.client;
import static scaffolding.ClientUtils.request;
import static scaffolding.MuAssert.assertEventually;

@Timeout(20)
class ExecutionDomainsTest {

    private @Nullable MuServer server;
    private final List<ExecutorService> executors = new ArrayList<>();

    @Test
    void http2IoRemainsResponsiveWhenTheHandlerExecutorIsSaturated() throws Exception {
        var handlerExecutor = track(new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(),
            namedThreads("handler-")
        ));
        var connectionExecutor = track(Executors.newFixedThreadPool(2, namedThreads("connection-")));
        var blockerStarted = new CountDownLatch(1);
        var releaseBlocker = new CountDownLatch(1);
        var releaseRejectListener = new CountDownLatch(1);
        var rejectedRequest = new CompletableFuture<RejectedRequest>();
        var completedResponses = new AtomicInteger();
        Future<?> blocker = handlerExecutor.submit(() -> {
            blockerStarted.countDown();
            try {
                releaseBlocker.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(blockerStarted.await(5, TimeUnit.SECONDS), is(true));

        server = httpServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .withHandlerExecutor(handlerExecutor)
            .withConnectionExecutor(connectionExecutor)
            .addRequestRejectListener(info -> {
                rejectedRequest.complete(info);
                try {
                    releaseRejectListener.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            })
            .addResponseCompleteListener(info -> completedResponses.incrementAndGet())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) ->
                response.write(Thread.currentThread().getName()))
            .start();

        try (var client = new H2Client();
             var con = client.connectClearText(server)) {
            byte[] firstPing = {0, 1, 2, 3, 4, 5, 6, 7};
            con.handshake()
                .writeFrame(new Http2Ping(false, firstPing))
                .flush();
            assertThat(con.readLogicalFrame(Http2Ping.class), equalTo(new Http2Ping(true, firstPing)));

            int port = Objects.requireNonNull(server.httpUri()).getPort();
            con.writeFrame(new Http2HeadersFrame(
                    1,
                    false,
                    RFCTestUtils.getHelloHeaders("http", port)
                ))
                .flush();

            var rejectedHeaders = RFCTestUtils.readIgnoringWindowUpdates(con, Http2HeadersFrame.class);
            assertThat(rejectedHeaders.streamId(), equalTo(1));
            assertThat(rejectedHeaders.headers().get(":status"), equalTo("503"));
            var rejectedBody = RFCTestUtils.readIgnoringWindowUpdates(con, Http2DataFrame.class);
            assertThat(rejectedBody.streamId(), equalTo(1));
            assertThat(rejectedBody.toUTF8(), equalTo("503 Service Unavailable"));
            assertThat(rejectedBody.endStream(), is(true));
            assertThat(server.stats().activeRequests().isEmpty(), is(true));
            assertThat(server.stats().completedRequests(), equalTo(0L));
            assertThat(completedResponses.get(), equalTo(0));

            var rejection = rejectedRequest.get(5, TimeUnit.SECONDS);
            assertThat(rejection.status(), equalTo(503));
            assertThat(rejection.reason(), equalTo("503 Service Unavailable"));
            assertThat(rejection.method(), equalTo(Optional.of("GET")));
            assertThat(rejection.uri().orElseThrow().getPath(), equalTo("/hello"));
            assertThat(rejection.connection().protocol(), equalTo("HTTP/2"));
            releaseRejectListener.countDown();

            byte[] secondPing = {7, 6, 5, 4, 3, 2, 1, 0};
            con.writeFrame(RFCTestUtils.utf8DataFrame(1, true, "discarded"))
                .writeFrame(new Http2Ping(false, secondPing))
                .flush();
            assertThat(con.readLogicalFrame(Http2Ping.class), equalTo(new Http2Ping(true, secondPing)));

            releaseBlocker.countDown();
            blocker.get(5, TimeUnit.SECONDS);

            con.writeFrame(new Http2HeadersFrame(
                    3,
                    true,
                    RFCTestUtils.getHelloHeaders("http", port)
                ))
                .flush();
            var acceptedHeaders = RFCTestUtils.readIgnoringWindowUpdates(con, Http2HeadersFrame.class);
            assertThat(acceptedHeaders.streamId(), equalTo(3));
            assertThat(acceptedHeaders.headers().get(":status"), equalTo("200"));
            var acceptedBody = RFCTestUtils.readIgnoringWindowUpdates(con, Http2DataFrame.class);
            assertThat(acceptedBody.toUTF8(), startsWith("handler-"));
            assertThat(RFCTestUtils.readIgnoringWindowUpdates(con, Http2DataFrame.class).endStream(), is(true));
        } finally {
            releaseBlocker.countDown();
            releaseRejectListener.countDown();
        }

        assertEventually(() -> server.stats().completedRequests(), equalTo(1L));
        assertEventually(completedResponses::get, equalTo(1));
        assertThat(server.stats().rejectedDueToOverload(), equalTo(1L));
    }

    @Test
    void singleThreadConnectionAndWriterExecutorsMakeIndependentProgress() throws Exception {
        var connectionExecutor = track(Executors.newSingleThreadExecutor(namedThreads("connection-")));
        var writerExecutor = track(Executors.newSingleThreadExecutor(namedThreads("h2-writer-")));

        server = httpServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .withConnectionExecutor(connectionExecutor)
            .withHttp2WriterExecutor(writerExecutor)
            .start();

        try (var client = new H2Client();
             var con = client.connectClearText(server)) {
            con.handshake();
            con.socket().setSoTimeout(1000);

            byte[] pingData = {0, 1, 2, 3, 4, 5, 6, 7};
            con.writeFrame(new Http2Ping(false, pingData)).flush();

            assertThat(con.readLogicalFrame(Http2Ping.class), equalTo(new Http2Ping(true, pingData)));
        }
    }

    @Test
    void oneWriterThreadCanServeMultipleLiveHttp2Connections() throws Exception {
        var connectionExecutor = track(Executors.newFixedThreadPool(2, namedThreads("connection-")));
        var writerExecutor = track(Executors.newSingleThreadExecutor(namedThreads("h2-writer-")));

        server = httpServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .withConnectionExecutor(connectionExecutor)
            .withHttp2WriterExecutor(writerExecutor)
            .start();

        try (var client = new H2Client();
             var first = client.connectClearText(server);
             var second = client.connectClearText(server)) {
            first.handshake();
            second.handshake();
            first.socket().setSoTimeout(5000);
            second.socket().setSoTimeout(5000);

            byte[] firstPing = {0, 1, 2, 3, 4, 5, 6, 7};
            byte[] secondPing = {7, 6, 5, 4, 3, 2, 1, 0};
            first.writeFrame(new Http2Ping(false, firstPing)).flush();
            second.writeFrame(new Http2Ping(false, secondPing)).flush();

            assertThat(first.readLogicalFrame(Http2Ping.class), equalTo(new Http2Ping(true, firstPing)));
            assertThat(second.readLogicalFrame(Http2Ping.class), equalTo(new Http2Ping(true, secondPing)));
        }
    }

    @Test
    void configuredExecutorsStayCallerOwnedAndHttp1UsesTheHandlerExecutor() throws Exception {
        var handlerExecutor = track(Executors.newSingleThreadExecutor(namedThreads("handler-")));
        var connectionExecutor = track(Executors.newCachedThreadPool(namedThreads("connection-")));
        var writerExecutor = track(Executors.newCachedThreadPool(namedThreads("h2-writer-")));
        var timerExecutor = track(Executors.newSingleThreadScheduledExecutor(namedThreads("timer-")));

        var builder = httpServer()
            .withHandlerExecutor(handlerExecutor)
            .withConnectionExecutor(connectionExecutor)
            .withHttp2WriterExecutor(writerExecutor)
            .withTimerExecutor(timerExecutor)
            .addHandler(Method.GET, "/", (request, response, pathParams) ->
                response.write(Thread.currentThread().getName()));
        assertThat(builder.connectionExecutor(), is(connectionExecutor));
        assertThat(builder.http2WriterExecutor(), is(writerExecutor));
        assertThat(builder.timerExecutor(), is(timerExecutor));

        server = builder.start();
        try (Response response = call(request(server.uri()))) {
            assertThat(response.code(), equalTo(200));
            assertThat(response.body().string(), startsWith("handler-"));
        }

        server.stop();
        server = null;
        assertThat(connectionExecutor.isShutdown(), is(false));
        assertThat(writerExecutor.isShutdown(), is(false));
        assertThat(handlerExecutor.isShutdown(), is(false));
        assertThat(timerExecutor.isShutdown(), is(false));
    }

    @Test
    void rejectedTimerSchedulingRollsBackStartupWithoutTakingCallerExecutors() throws Exception {
        var handlerExecutor = track(Executors.newSingleThreadExecutor(namedThreads("handler-")));
        var connectionExecutor = track(Executors.newCachedThreadPool(namedThreads("connection-")));
        var writerExecutor = track(Executors.newCachedThreadPool(namedThreads("h2-writer-")));
        var timerExecutor = track(Executors.newSingleThreadScheduledExecutor(namedThreads("timer-")));
        timerExecutor.shutdown();
        int port = availablePort();

        assertThrows(RejectedExecutionException.class, () -> httpServer()
            .withHttpPort(port)
            .withHandlerExecutor(handlerExecutor)
            .withConnectionExecutor(connectionExecutor)
            .withHttp2WriterExecutor(writerExecutor)
            .withTimerExecutor(timerExecutor)
            .start());

        assertThat(handlerExecutor.isShutdown(), is(false));
        assertThat(connectionExecutor.isShutdown(), is(false));
        assertThat(writerExecutor.isShutdown(), is(false));
        try (var replacementListener = new ServerSocket(port)) {
            assertThat(replacementListener.isBound(), is(true));
        }
    }

    @RepeatedTest(5)
    void timedConnectionWorkIsNotStarvedByTheHandlerExecutor() throws Exception {
        var handlerExecutor = track(Executors.newSingleThreadExecutor(namedThreads("handler-")));
        var connectionExecutor = track(Executors.newCachedThreadPool(namedThreads("connection-")));
        var pongReceived = new CountDownLatch(1);
        var serverSocket = new SimpleWebSocket() {
            @Override
            public void onText(String message) {
            }

            @Override
            public void onBinary(ByteBuffer payload) {
            }

            @Override
            public void onPong(ByteBuffer payload) throws Exception {
                super.onPong(payload);
                pongReceived.countDown();
            }
        };

        server = httpServer()
            .withHandlerExecutor(handlerExecutor)
            .withConnectionExecutor(connectionExecutor)
            .addHandler(webSocketHandler((request, responseHeaders) -> serverSocket)
                .withPingInterval(10, TimeUnit.MILLISECONDS))
            .start();

        String websocketUrl = "ws" + server.uri().toString().substring(4);
        WebSocket clientSocket = client.newWebSocket(
            request().url(websocketUrl).build(),
            new WebSocketListener() {
            }
        );
        try {
            assertThat(pongReceived.await(2, TimeUnit.SECONDS), is(true));
        } finally {
            clientSocket.cancel();
        }
    }

    private <T extends ExecutorService> T track(T executor) {
        executors.add(executor);
        return executor;
    }

    private static int availablePort() throws IOException {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static java.util.concurrent.ThreadFactory namedThreads(String prefix) {
        var count = new AtomicInteger();
        return runnable -> new Thread(runnable, prefix + count.incrementAndGet());
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop();
        }
        for (var executor : executors) {
            executor.shutdownNow();
        }
    }
}
