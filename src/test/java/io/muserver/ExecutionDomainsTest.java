package io.muserver;

import okhttp3.Response;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.BufferedSink;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import scaffolding.Http1Client;

import java.io.EOFException;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
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
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.instanceOf;
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
    void anIdleHttp1ConnectionDoesNotRetainAHandlerWorker() throws Exception {
        var handlerExecutor = track(Executors.newSingleThreadExecutor(namedThreads("handler-")));
        var connectionExecutor = track(Executors.newCachedThreadPool(namedThreads("connection-")));
        var clientExecutor = track(Executors.newSingleThreadExecutor(namedThreads("client-")));
        server = httpServer()
            .withHandlerExecutor(handlerExecutor)
            .withConnectionExecutor(connectionExecutor)
            .addHandler(Method.GET, "/", (request, response, pathParams) ->
                response.write(Thread.currentThread().getName()))
            .start();

        try (var first = Http1Client.connect(server);
             var second = Http1Client.connect(server)) {
            first.writeRequestLine(Method.GET, "/").flushHeaders();
            assertThat(first.readLine(), equalTo("HTTP/1.1 200 OK"));
            assertThat(first.readBody(first.readHeaders()), startsWith("handler-"));

            Future<String> secondResponse = clientExecutor.submit(() -> {
                second.writeRequestLine(Method.GET, "/").flushHeaders();
                assertThat(second.readLine(), equalTo("HTTP/1.1 200 OK"));
                return second.readBody(second.readHeaders());
            });
            assertThat(secondResponse.get(2, TimeUnit.SECONDS), startsWith("handler-"));
        }
    }

    @Test
    void aSharedConnectionAndHandlerExecutorDoesNotSubmitBackToItself() throws Exception {
        var sharedExecutor = track(Executors.newSingleThreadExecutor(namedThreads("shared-")));
        server = httpServer()
            .withHandlerExecutor(sharedExecutor)
            .withConnectionExecutor(sharedExecutor)
            .addHandler(Method.GET, "/", (request, response, pathParams) ->
                response.write(Thread.currentThread().getName()))
            .start();

        try (var client = Http1Client.connect(server)) {
            client.writeRequestLine(Method.GET, "/").flushHeaders();
            assertThat(client.readLine(), equalTo("HTTP/1.1 200 OK"));
            assertThat(client.readBody(client.readHeaders()), startsWith("shared-"));
        }
    }

    @Test
    void oneAsyncWorkerCanReadAndEchoARequestBody() throws Exception {
        var asyncExecutor = track(Executors.newSingleThreadExecutor(namedThreads("async-")));
        var callbackThreads = new CopyOnWriteArrayList<String>();
        byte[] payload = new byte[20_000];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) ('a' + (i % 26));
        }
        server = httpServer()
            .withAsyncExecutor(asyncExecutor)
            .addHandler(Method.POST, "/", (request, response, pathParams) -> {
                AsyncHandle handle = request.handleAsync();
                handle.setReadListener(new RequestBodyListener() {
                    @Override
                    public void onDataReceived(ByteBuffer data, DoneCallback doneCallback) {
                        callbackThreads.add(Thread.currentThread().getName());
                        handle.write(data, error -> {
                            callbackThreads.add(Thread.currentThread().getName());
                            doneCallback.onComplete(error);
                        });
                    }

                    @Override
                    public void onComplete() {
                        callbackThreads.add(Thread.currentThread().getName());
                        handle.complete();
                    }

                    @Override
                    public void onError(Throwable t) {
                        handle.complete(t);
                    }
                });
            })
            .start();

        RequestBody body = new RequestBody() {
            @Override
            public MediaType contentType() {
                return null;
            }

            @Override
            public void writeTo(BufferedSink sink) throws IOException {
                sink.write(payload);
            }
        };
        try (Response response = call(request(server.uri()).post(body))) {
            assertThat(response.body().bytes(), equalTo(payload));
        }
        assertThat(callbackThreads.size(), greaterThanOrEqualTo(3));
        for (String callbackThread : callbackThreads) {
            assertThat(callbackThread, startsWith("async-"));
        }
    }

    @Test
    void rejectedAsyncWritesFailTheRequestAndInvokeTheCallback() throws Exception {
        var asyncExecutor = track(Executors.newSingleThreadExecutor(namedThreads("async-")));
        asyncExecutor.shutdown();
        var callbackFailure = new CompletableFuture<Throwable>();
        server = httpServer()
            .withAsyncExecutor(asyncExecutor)
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                AsyncHandle handle = request.handleAsync();
                handle.write(ByteBuffer.wrap(new byte[]{1}), error -> {
                    if (error != null) {
                        callbackFailure.complete(error);
                    }
                });
            })
            .start();

        try (Response response = call(request(server.uri()))) {
            assertThat(response.code(), is(500));
        }
        assertThat(callbackFailure.get(5, TimeUnit.SECONDS), instanceOf(RejectedExecutionException.class));
    }

    @SuppressWarnings("deprecation")
    @Test
    void deprecatedWebSocketAdaptersUseTheAsyncExecutorWithoutSelfDeadlock() throws Exception {
        var asyncExecutor = track(Executors.newSingleThreadExecutor(namedThreads("async-")));
        var callbackThreads = new CopyOnWriteArrayList<String>();
        var clientMessage = new CompletableFuture<String>();
        server = httpServer()
            .withAsyncExecutor(asyncExecutor)
            .addHandler(webSocketHandler((request, responseHeaders) -> new BaseWebSocket() {
                @Override
                public void onText(String message, boolean isLast, DoneCallback onComplete) {
                    callbackThreads.add(Thread.currentThread().getName());
                    session().sendText(message, error -> {
                        callbackThreads.add(Thread.currentThread().getName());
                        onComplete.onComplete(error);
                    });
                }
            }))
            .start();

        String websocketUrl = "ws" + server.uri().toString().substring(4);
        WebSocket webSocket = client.newWebSocket(
            request().url(websocketUrl).build(),
            new WebSocketListener() {
                @Override
                public void onMessage(WebSocket webSocket, String text) {
                    clientMessage.complete(text);
                }
            }
        );
        webSocket.send("hello");
        assertThat(clientMessage.get(5, TimeUnit.SECONDS), equalTo("hello"));
        webSocket.close(1000, "Done");
        assertThat(callbackThreads.size(), is(2));
        for (String callbackThread : callbackThreads) {
            assertThat(callbackThread, startsWith("async-"));
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
    void idleTimeoutIsNotStarvedByAConnectionReader() throws Exception {
        var connectionExecutor = track(Executors.newSingleThreadExecutor(namedThreads("connection-")));
        var writerExecutor = track(Executors.newSingleThreadExecutor(namedThreads("h2-writer-")));
        var maintenanceExecutor = track(Executors.newSingleThreadExecutor(namedThreads("maintenance-")));

        server = httpServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .withConnectionExecutor(connectionExecutor)
            .withHttp2WriterExecutor(writerExecutor)
            .withConnectionMaintenanceExecutor(maintenanceExecutor)
            .withIdleTimeout(100, TimeUnit.MILLISECONDS)
            .start();

        try (var client = new H2Client();
             var con = client.connectClearText(server)) {
            con.handshake();
            con.socket().setSoTimeout(2000);

            assertThrows(EOFException.class, con::readLogicalFrame);
        }
    }

    @Test
    void configuredExecutorsStayCallerOwnedAndHttp1UsesTheHandlerExecutor() throws Exception {
        var handlerExecutor = track(Executors.newSingleThreadExecutor(namedThreads("handler-")));
        var asyncExecutor = track(Executors.newSingleThreadExecutor(namedThreads("async-")));
        var connectionExecutor = track(Executors.newCachedThreadPool(namedThreads("connection-")));
        var writerExecutor = track(Executors.newCachedThreadPool(namedThreads("h2-writer-")));
        var maintenanceExecutor = track(Executors.newCachedThreadPool(namedThreads("maintenance-")));
        var timerExecutor = track(Executors.newSingleThreadScheduledExecutor(namedThreads("timer-")));

        var builder = httpServer()
            .withHandlerExecutor(handlerExecutor)
            .withAsyncExecutor(asyncExecutor)
            .withConnectionExecutor(connectionExecutor)
            .withHttp2WriterExecutor(writerExecutor)
            .withConnectionMaintenanceExecutor(maintenanceExecutor)
            .withTimerExecutor(timerExecutor)
            .addHandler(Method.GET, "/", (request, response, pathParams) ->
                response.write(Thread.currentThread().getName()));
        assertThat(builder.connectionExecutor(), is(connectionExecutor));
        assertThat(builder.asyncExecutor(), is(asyncExecutor));
        assertThat(builder.http2WriterExecutor(), is(writerExecutor));
        assertThat(builder.connectionMaintenanceExecutor(), is(maintenanceExecutor));
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
        assertThat(maintenanceExecutor.isShutdown(), is(false));
        assertThat(handlerExecutor.isShutdown(), is(false));
        assertThat(asyncExecutor.isShutdown(), is(false));
        assertThat(timerExecutor.isShutdown(), is(false));
    }

    @Test
    void serverOwnedExecutorsAreShutDownWithTheServer() throws Exception {
        server = httpServer().start();
        var serverImpl = (Mu3ServerImpl) server;
        List<ExecutorService> serverOwnedExecutors = List.of(
            getField(serverImpl, "handlerExecutor", ExecutorService.class),
            getField(serverImpl, "asyncExecutor", ExecutorService.class),
            getField(serverImpl, "connectionExecutor", ExecutorService.class),
            getField(serverImpl, "http2WriterExecutor", ExecutorService.class),
            getField(serverImpl, "connectionMaintenanceExecutor", ExecutorService.class),
            getField(serverImpl, "timerExecutor", ExecutorService.class)
        );

        server.stop();
        server = null;

        for (ExecutorService executor : serverOwnedExecutors) {
            assertThat(executor.isShutdown(), is(true));
        }
    }

    @Test
    void rejectedTimerSchedulingRollsBackStartupWithoutTakingCallerExecutors() throws Exception {
        var handlerExecutor = track(Executors.newSingleThreadExecutor(namedThreads("handler-")));
        var asyncExecutor = track(Executors.newSingleThreadExecutor(namedThreads("async-")));
        var connectionExecutor = track(Executors.newCachedThreadPool(namedThreads("connection-")));
        var writerExecutor = track(Executors.newCachedThreadPool(namedThreads("h2-writer-")));
        var maintenanceExecutor = track(Executors.newCachedThreadPool(namedThreads("maintenance-")));
        var timerExecutor = track(Executors.newSingleThreadScheduledExecutor(namedThreads("timer-")));
        timerExecutor.shutdown();
        int port = availablePort();

        assertThrows(RejectedExecutionException.class, () -> httpServer()
            .withHttpPort(port)
            .withHandlerExecutor(handlerExecutor)
            .withAsyncExecutor(asyncExecutor)
            .withConnectionExecutor(connectionExecutor)
            .withHttp2WriterExecutor(writerExecutor)
            .withConnectionMaintenanceExecutor(maintenanceExecutor)
            .withTimerExecutor(timerExecutor)
            .start());

        assertThat(handlerExecutor.isShutdown(), is(false));
        assertThat(asyncExecutor.isShutdown(), is(false));
        assertThat(connectionExecutor.isShutdown(), is(false));
        assertThat(writerExecutor.isShutdown(), is(false));
        assertThat(maintenanceExecutor.isShutdown(), is(false));
        try (var replacementListener = new ServerSocket(port)) {
            assertThat(replacementListener.isBound(), is(true));
        }
    }

    @Test
    void listenerCreationFailureRollsBackEarlierListenersWithoutTakingCallerExecutors() throws Exception {
        var handlerExecutor = track(Executors.newSingleThreadExecutor(namedThreads("handler-")));
        var asyncExecutor = track(Executors.newSingleThreadExecutor(namedThreads("async-")));
        var connectionExecutor = track(Executors.newCachedThreadPool(namedThreads("connection-")));
        var writerExecutor = track(Executors.newCachedThreadPool(namedThreads("h2-writer-")));
        var maintenanceExecutor = track(Executors.newCachedThreadPool(namedThreads("maintenance-")));
        var timerExecutor = track(Executors.newSingleThreadScheduledExecutor(namedThreads("timer-")));
        int firstPort = availablePort();

        try (var occupiedListener = new ServerSocket(0)) {
            assertThrows(MuException.class, () -> MuServerBuilder.muServer()
                .withHttpsPort(firstPort)
                .withHttpPort(occupiedListener.getLocalPort())
                .withHandlerExecutor(handlerExecutor)
                .withAsyncExecutor(asyncExecutor)
                .withConnectionExecutor(connectionExecutor)
                .withHttp2WriterExecutor(writerExecutor)
                .withConnectionMaintenanceExecutor(maintenanceExecutor)
                .withTimerExecutor(timerExecutor)
                .start());
        }

        assertThat(handlerExecutor.isShutdown(), is(false));
        assertThat(asyncExecutor.isShutdown(), is(false));
        assertThat(connectionExecutor.isShutdown(), is(false));
        assertThat(writerExecutor.isShutdown(), is(false));
        assertThat(maintenanceExecutor.isShutdown(), is(false));
        assertThat(timerExecutor.isShutdown(), is(false));
        try (var replacementListener = new ServerSocket(firstPort)) {
            assertThat(replacementListener.isBound(), is(true));
        }
    }

    @RepeatedTest(5)
    void timedConnectionWorkIsNotStarvedByTheHandlerExecutor() throws Exception {
        var handlerExecutor = track(Executors.newSingleThreadExecutor(namedThreads("handler-")));
        var connectionExecutor = track(Executors.newCachedThreadPool(namedThreads("connection-")));
        var maintenanceExecutor = track(Executors.newSingleThreadExecutor(namedThreads("maintenance-")));
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
            .withConnectionMaintenanceExecutor(maintenanceExecutor)
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

    private static <T> T getField(Object target, String name, Class<T> type) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(target));
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
