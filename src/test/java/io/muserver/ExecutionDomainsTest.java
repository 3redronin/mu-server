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
import java.util.concurrent.Phaser;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
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
        var asyncExecutor = track(Executors.newSingleThreadExecutor(namedThreads("async-")));
        var connectionExecutor = track(Executors.newFixedThreadPool(2, namedThreads("connection-")));
        var blockerStarted = new CountDownLatch(1);
        var releaseBlocker = new CountDownLatch(1);
        var releaseRejectListener = new CountDownLatch(1);
        var rejectedRequest = new CompletableFuture<RejectedRequest>();
        var rejectionThread = new CompletableFuture<String>();
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
            .withAsyncExecutor(asyncExecutor)
            .withConnectionExecutor(connectionExecutor)
            .addRequestRejectListener(info -> {
                rejectionThread.complete(Thread.currentThread().getName());
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
            con.socket().setSoTimeout(1000);
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
            assertThat(rejectionThread.get(5, TimeUnit.SECONDS), startsWith("async-"));

            byte[] secondPing = {7, 6, 5, 4, 3, 2, 1, 0};
            con.writeFrame(RFCTestUtils.utf8DataFrame(1, true, "discarded"))
                .writeFrame(new Http2Ping(false, secondPing))
                .flush();
            assertThat(con.readLogicalFrame(Http2Ping.class), equalTo(new Http2Ping(true, secondPing)));
            releaseRejectListener.countDown();

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
    void rejectedAsyncExecutorFallsBackWithoutBlockingTheHttp2Reader() throws Exception {
        var asyncExecutor = track(Executors.newSingleThreadExecutor(namedThreads("rejected-async-")));
        asyncExecutor.shutdown();
        var handlerExecutor = track(Executors.newSingleThreadExecutor(namedThreads("handler-")));
        var connectionExecutor = track(Executors.newSingleThreadExecutor(namedThreads("connection-")));
        var releaseListener = new CountDownLatch(1);
        var rejectionThread = new CompletableFuture<String>();
        server = httpServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .withMaxHeadersSize(1024)
            .withAsyncExecutor(asyncExecutor)
            .withHandlerExecutor(handlerExecutor)
            .withConnectionExecutor(connectionExecutor)
            .addRequestRejectListener(info -> {
                rejectionThread.complete(Thread.currentThread().getName());
                try {
                    releaseListener.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            })
            .start();

        try (var client = new H2Client();
             var con = client.connectClearText(server)) {
            con.handshake();
            con.socket().setSoTimeout(1000);
            FieldBlock headers = RFCTestUtils.getHelloHeaders("http", server.uri().getPort());
            headers.add("x-big", "a".repeat(2000));
            con.writeFrame(new Http2HeadersFrame(1, true, headers)).flush();

            Http2HeadersFrame response =
                RFCTestUtils.readIgnoringWindowUpdates(con, Http2HeadersFrame.class);
            assertThat(response.headers().get(":status"), equalTo("431"));
            assertThat(
                RFCTestUtils.readIgnoringWindowUpdates(con, Http2DataFrame.class).endStream(),
                is(true)
            );
            assertThat(rejectionThread.get(5, TimeUnit.SECONDS), startsWith("handler-"));

            byte[] pingData = {0, 1, 2, 3, 4, 5, 6, 7};
            con.writeFrame(new Http2Ping(false, pingData)).flush();
            assertThat(
                RFCTestUtils.readIgnoringWindowUpdates(con, Http2Ping.class),
                equalTo(new Http2Ping(true, pingData))
            );
        } finally {
            releaseListener.countDown();
        }
    }

    @Test
    void http1RejectionResponsePrecedesFallbackListenerDelivery() throws Exception {
        var rejectedAsyncExecutor =
            track(Executors.newSingleThreadExecutor(namedThreads("rejected-async-")));
        rejectedAsyncExecutor.shutdown();
        var clientExecutor = track(Executors.newSingleThreadExecutor(namedThreads("client-")));
        var listenerEntered = new CountDownLatch(1);
        var releaseListener = new CountDownLatch(1);
        server = httpServer()
            .withMaxHeadersSize(1024)
            .withAsyncExecutor(rejectedAsyncExecutor)
            .addRequestRejectListener(info -> {
                listenerEntered.countDown();
                try {
                    releaseListener.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            })
            .start();

        try (var client = Http1Client.connect(server)) {
            client.writeRequestLine(Method.GET, "/")
                .writeHeader("x-big", "a".repeat(2000))
                .flushHeaders();
            assertThat(listenerEntered.await(5, TimeUnit.SECONDS), is(true));
            Future<String> responseLine = clientExecutor.submit(client::readLine);
            assertThat(responseLine.get(2, TimeUnit.SECONDS), startsWith("HTTP/1.1 431"));
        } finally {
            releaseListener.countDown();
        }
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
    void gracefulStopWaitsForDispatchedRequestRejectionListeners() throws Exception {
        var asyncExecutor = track(Executors.newSingleThreadExecutor(namedThreads("async-")));
        var connectionExecutor = track(Executors.newSingleThreadExecutor(namedThreads("connection-")));
        var clientExecutor = track(Executors.newSingleThreadExecutor(namedThreads("client-")));
        var listenerEntered = new CountDownLatch(1);
        var releaseListener = new CountDownLatch(1);
        server = httpServer()
            .withMaxHeadersSize(1024)
            .withAsyncExecutor(asyncExecutor)
            .withConnectionExecutor(connectionExecutor)
            .addRequestRejectListener(info -> {
                listenerEntered.countDown();
                try {
                    releaseListener.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            })
            .start();

        try {
            try (var client = Http1Client.connect(server)) {
                client.writeRequestLine(Method.GET, "/")
                    .writeHeader("x-big", "a".repeat(2000))
                    .flushHeaders();
                assertThat(client.readLine(), startsWith("HTTP/1.1 431"));
                client.readBody(client.readHeaders());
                assertThat(listenerEntered.await(5, TimeUnit.SECONDS), is(true));
            }

            MuServer runningServer = Objects.requireNonNull(server);
            Future<Boolean> stopped = clientExecutor.submit(() ->
                runningServer.stop(2, TimeUnit.SECONDS)
            );
            assertThrows(TimeoutException.class, () ->
                stopped.get(100, TimeUnit.MILLISECONDS)
            );
            releaseListener.countDown();
            assertThat(stopped.get(5, TimeUnit.SECONDS), is(true));
            server = null;
        } finally {
            releaseListener.countDown();
        }
    }

    @Test
    void detachedTaskWaitDoesNotWaitOnANewEmptyPhase() {
        Phaser tasks = new Phaser(1) {
            private boolean advanceBeforeReturningParties = true;

            @Override
            public int getRegisteredParties() {
                int parties = super.getRegisteredParties();
                if (advanceBeforeReturningParties) {
                    advanceBeforeReturningParties = false;
                    arriveAndDeregister();
                }
                return parties;
            }
        };

        assertThat(
            Mu3ServerImpl.awaitDetachedApplicationTasks(
                tasks,
                System.currentTimeMillis() + 100
            ),
            is(true)
        );
    }

    @Test
    void requestRejectionListenerCanStopItsServerWithoutWaitingForItself() throws Exception {
        var runningServer = new CompletableFuture<MuServer>();
        var stopResult = new CompletableFuture<Boolean>();
        server = httpServer()
            .withMaxHeadersSize(1024)
            .addRequestRejectListener(info -> {
                try {
                    stopResult.complete(
                        runningServer.get().stop(2, TimeUnit.SECONDS)
                    );
                } catch (Throwable failure) {
                    stopResult.completeExceptionally(failure);
                }
            })
            .start();
        runningServer.complete(server);

        try (var client = Http1Client.connect(server)) {
            client.writeRequestLine(Method.GET, "/")
                .writeHeader("x-big", "a".repeat(2000))
                .flushHeaders();
            assertThat(client.readLine(), startsWith("HTTP/1.1 431"));
            assertThat(stopResult.get(1, TimeUnit.SECONDS), is(true));
            server = null;
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
        var completionThread = new CompletableFuture<String>();
        server = httpServer()
            .withHandlerExecutor(sharedExecutor)
            .withConnectionExecutor(sharedExecutor)
            .addResponseCompleteListener(info ->
                completionThread.complete(Thread.currentThread().getName())
            )
            .addHandler(Method.GET, "/", (request, response, pathParams) ->
                response.write(Thread.currentThread().getName()))
            .start();

        try (var client = Http1Client.connect(server)) {
            client.writeRequestLine(Method.GET, "/").flushHeaders();
            assertThat(client.readLine(), equalTo("HTTP/1.1 200 OK"));
            assertThat(client.readBody(client.readHeaders()), startsWith("shared-"));
        }
        assertThat(completionThread.get(5, TimeUnit.SECONDS), startsWith("shared-"));
    }

    @Test
    void http1ResponseCompletionCallbacksUseTheHandlerExecutor() throws Exception {
        var handlerExecutor = track(Executors.newSingleThreadExecutor(namedThreads("handler-")));
        var connectionExecutor = track(Executors.newCachedThreadPool(namedThreads("connection-")));
        var responseCompletionThread = new CompletableFuture<String>();
        var serverCompletionThread = new CompletableFuture<String>();
        server = httpServer()
            .withHandlerExecutor(handlerExecutor)
            .withConnectionExecutor(connectionExecutor)
            .addResponseCompleteListener(info ->
                serverCompletionThread.complete(Thread.currentThread().getName())
            )
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.addCompletionListener(info ->
                    responseCompletionThread.complete(Thread.currentThread().getName())
                );
                response.write("done");
            })
            .start();

        try (var client = Http1Client.connect(server)) {
            client.writeRequestLine(Method.GET, "/").flushHeaders();
            assertThat(client.readLine(), equalTo("HTTP/1.1 200 OK"));
            assertThat(client.readBody(client.readHeaders()), equalTo("done"));
        }

        assertThat(responseCompletionThread.get(5, TimeUnit.SECONDS), startsWith("handler-"));
        assertThat(serverCompletionThread.get(5, TimeUnit.SECONDS), startsWith("handler-"));
    }

    @Test
    void aSlowHttp1CompletionListenerDoesNotDelayTheNextRequest() throws Exception {
        var handlerExecutor = track(Executors.newFixedThreadPool(2, namedThreads("handler-")));
        var connectionExecutor = track(Executors.newSingleThreadExecutor(namedThreads("connection-")));
        var clientExecutor = track(Executors.newSingleThreadExecutor(namedThreads("client-")));
        var firstCompletionStarted = new CountDownLatch(1);
        var releaseFirstCompletion = new CountDownLatch(1);
        server = httpServer()
            .withHandlerExecutor(handlerExecutor)
            .withConnectionExecutor(connectionExecutor)
            .addResponseCompleteListener(info -> {
                if (info.request().relativePath().equals("/first")) {
                    firstCompletionStarted.countDown();
                    try {
                        releaseFirstCompletion.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            })
            .addHandler((request, response) -> {
                response.write(request.relativePath());
                return true;
            })
            .start();

        try (var client = Http1Client.connect(server)) {
            client.writeRequestLine(Method.GET, "/first").flushHeaders();
            assertThat(client.readLine(), equalTo("HTTP/1.1 200 OK"));
            assertThat(client.readBody(client.readHeaders()), equalTo("/first"));
            assertThat(firstCompletionStarted.await(5, TimeUnit.SECONDS), is(true));
            assertThat(server.stats().completedRequests(), equalTo(1L));

            Future<String> secondResponse = clientExecutor.submit(() -> {
                client.writeRequestLine(Method.GET, "/second").flushHeaders();
                assertThat(client.readLine(), equalTo("HTTP/1.1 200 OK"));
                return client.readBody(client.readHeaders());
            });
            assertThat(secondResponse.get(2, TimeUnit.SECONDS), equalTo("/second"));
        } finally {
            releaseFirstCompletion.countDown();
        }
    }

    @Test
    void gracefulStopWaitsForDispatchedHttp1CompletionListeners() throws Exception {
        var handlerExecutor = track(Executors.newFixedThreadPool(2, namedThreads("handler-")));
        var connectionExecutor = track(Executors.newSingleThreadExecutor(namedThreads("connection-")));
        var clientExecutor = track(Executors.newSingleThreadExecutor(namedThreads("client-")));
        var completionStarted = new CountDownLatch(1);
        var releaseCompletion = new CountDownLatch(1);
        server = httpServer()
            .withHandlerExecutor(handlerExecutor)
            .withConnectionExecutor(connectionExecutor)
            .addResponseCompleteListener(info -> {
                completionStarted.countDown();
                try {
                    releaseCompletion.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            })
            .addHandler(Method.GET, "/", (request, response, pathParams) ->
                response.write("done")
            )
            .start();

        try (var client = Http1Client.connect(server)) {
            client.writeRequestLine(Method.GET, "/").flushHeaders();
            assertThat(client.readLine(), equalTo("HTTP/1.1 200 OK"));
            assertThat(client.readBody(client.readHeaders()), equalTo("done"));
            assertThat(completionStarted.await(5, TimeUnit.SECONDS), is(true));

            MuServer runningServer = Objects.requireNonNull(server);
            Future<Boolean> stopped = clientExecutor.submit(() ->
                runningServer.stop(2, TimeUnit.SECONDS)
            );
            assertThrows(TimeoutException.class, () ->
                stopped.get(100, TimeUnit.MILLISECONDS)
            );
            releaseCompletion.countDown();
            assertThat(stopped.get(5, TimeUnit.SECONDS), is(true));
            server = null;
        } finally {
            releaseCompletion.countDown();
        }
    }

    @Test
    void rejectedHandlerDispatchFallsBackWithoutRunningCompletionListenersOnTheHttp1Reader() throws Exception {
        var handlerExecutor = track(Executors.newSingleThreadExecutor(namedThreads("handler-")));
        var asyncExecutor = track(Executors.newSingleThreadExecutor(namedThreads("async-")));
        var connectionExecutor = track(Executors.newSingleThreadExecutor(namedThreads("connection-")));
        var completionThread = new CompletableFuture<String>();
        server = httpServer()
            .withHandlerExecutor(handlerExecutor)
            .withAsyncExecutor(asyncExecutor)
            .withConnectionExecutor(connectionExecutor)
            .addResponseCompleteListener(info ->
                completionThread.complete(Thread.currentThread().getName())
            )
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.write("done");
                handlerExecutor.shutdown();
            })
            .start();

        try (var client = Http1Client.connect(server)) {
            client.writeRequestLine(Method.GET, "/").flushHeaders();
            assertThat(client.readLine(), equalTo("HTTP/1.1 200 OK"));
            assertThat(client.readBody(client.readHeaders()), equalTo("done"));
        }
        assertThat(completionThread.get(5, TimeUnit.SECONDS), startsWith("async-"));
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

    @Test
    void aSuspendedHttp1ExchangeDoesNotRetainAHandlerWorker() throws Exception {
        var handlerExecutor = track(Executors.newSingleThreadExecutor(namedThreads("handler-")));
        var clientExecutor = track(Executors.newSingleThreadExecutor(namedThreads("client-")));
        var suspendedHandle = new CompletableFuture<AsyncHandle>();
        var completionThread = new CompletableFuture<String>();
        server = httpServer()
            .withHandlerExecutor(handlerExecutor)
            .addHandler((request, response) -> {
                if (request.relativePath().equals("/suspend")) {
                    AsyncHandle handle = request.handleAsync();
                    handle.addResponseCompleteHandler(info ->
                        completionThread.complete(Thread.currentThread().getName())
                    );
                    suspendedHandle.complete(handle);
                } else {
                    response.write(Thread.currentThread().getName());
                }
                return true;
            })
            .start();

        try (var first = Http1Client.connect(server);
             var second = Http1Client.connect(server)) {
            first.writeRequestLine(Method.GET, "/suspend").flushHeaders();
            AsyncHandle handle = suspendedHandle.get(5, TimeUnit.SECONDS);
            try {
                Future<String> secondResponse = clientExecutor.submit(() -> {
                    second.writeRequestLine(Method.GET, "/second").flushHeaders();
                    assertThat(second.readLine(), equalTo("HTTP/1.1 200 OK"));
                    return second.readBody(second.readHeaders());
                });
                assertThat(secondResponse.get(2, TimeUnit.SECONDS), startsWith("handler-"));
            } finally {
                handle.complete();
            }

            assertThat(first.readLine(), equalTo("HTTP/1.1 200 OK"));
            assertThat(first.readBody(first.readHeaders()), equalTo(""));
            assertThat(completionThread.get(5, TimeUnit.SECONDS), startsWith("handler-"));
        }
    }

    @Test
    void rejectedHandlerDispatchesHttp1AsyncFailureHandlingToTheAsyncExecutor() throws Exception {
        var handlerExecutor = track(Executors.newSingleThreadExecutor(namedThreads("handler-")));
        var asyncExecutor = track(Executors.newSingleThreadExecutor(namedThreads("async-")));
        var suspendedHandle = new CompletableFuture<AsyncHandle>();
        var exceptionThread = new CompletableFuture<String>();
        server = httpServer()
            .withHandlerExecutor(handlerExecutor)
            .withAsyncExecutor(asyncExecutor)
            .withExceptionHandler((request, response, exception) -> {
                exceptionThread.complete(Thread.currentThread().getName());
                response.status(500);
                response.write("handled");
                return true;
            })
            .addHandler((request, response) -> {
                suspendedHandle.complete(request.handleAsync());
                handlerExecutor.shutdown();
                return true;
            })
            .start();

        try (var client = Http1Client.connect(server)) {
            client.writeRequestLine(Method.GET, "/").flushHeaders();
            suspendedHandle.get(5, TimeUnit.SECONDS).complete(new IOException("async failure"));

            assertThat(client.readLine(), equalTo("HTTP/1.1 500 Internal Server Error"));
            assertThat(client.readBody(client.readHeaders()), equalTo("handled"));
            assertThat(exceptionThread.get(5, TimeUnit.SECONDS), startsWith("async-"));
        }
    }

    @Test
    void aSuspendedHttp2ExchangeDoesNotRetainAHandlerWorker() throws Exception {
        var handlerExecutor = track(Executors.newSingleThreadExecutor(namedThreads("handler-")));
        var suspendedHandle = new CompletableFuture<AsyncHandle>();
        var completionThread = new CompletableFuture<String>();
        server = httpServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .withHandlerExecutor(handlerExecutor)
            .addHandler((request, response) -> {
                if (request.relativePath().equals("/suspend")) {
                    AsyncHandle handle = request.handleAsync();
                    handle.addResponseCompleteHandler(info ->
                        completionThread.complete(Thread.currentThread().getName())
                    );
                    suspendedHandle.complete(handle);
                } else {
                    response.write(Thread.currentThread().getName());
                }
                return true;
            })
            .start();

        try (var h2Client = new H2Client();
             var connection = h2Client.connectClearText(server)) {
            connection.handshake();
            connection.socket().setSoTimeout(2000);
            int port = server.uri().getPort();
            FieldBlock firstHeaders = RFCTestUtils.getHelloHeaders("http", port);
            firstHeaders.set(":path", "/suspend");
            connection.writeFrame(new Http2HeadersFrame(1, true, firstHeaders)).flush();
            AsyncHandle handle = suspendedHandle.get(5, TimeUnit.SECONDS);
            try {
                FieldBlock secondHeaders = RFCTestUtils.getHelloHeaders("http", port);
                secondHeaders.set(":path", "/second");
                connection.writeFrame(new Http2HeadersFrame(3, true, secondHeaders)).flush();

                Http2HeadersFrame responseHeaders =
                    RFCTestUtils.readIgnoringWindowUpdates(connection, Http2HeadersFrame.class);
                assertThat(responseHeaders.streamId(), is(3));
                assertThat(responseHeaders.headers().get(":status"), equalTo("200"));
                Http2DataFrame responseData =
                    RFCTestUtils.readIgnoringWindowUpdates(connection, Http2DataFrame.class);
                assertThat(responseData.streamId(), is(3));
                assertThat(responseData.toUTF8(), startsWith("handler-"));
            } finally {
                handle.complete();
            }
            assertThat(completionThread.get(5, TimeUnit.SECONDS), startsWith("handler-"));
        }
    }

    @Test
    void synchronousHttp2CompletionDoesNotResubmitFromItsApplicationWorker() throws Exception {
        var applicationExecutor = track(new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(),
            namedThreads("application-")
        ));
        var responseCompletionThread = new CompletableFuture<String>();
        var completionThread = new CompletableFuture<String>();
        server = httpServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .withHandlerExecutor(applicationExecutor)
            .withAsyncExecutor(applicationExecutor)
            .addResponseCompleteListener(info ->
                completionThread.complete(Thread.currentThread().getName())
            )
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> {
                response.addCompletionListener(info ->
                    responseCompletionThread.complete(Thread.currentThread().getName())
                );
                response.write("done");
            })
            .start();

        try (var h2Client = new H2Client();
             var connection = h2Client.connectClearText(server)) {
            connection.handshake();
            connection.socket().setSoTimeout(2000);
            connection.writeFrame(new Http2HeadersFrame(
                1,
                true,
                RFCTestUtils.getHelloHeaders("http", server.uri().getPort())
            )).flush();

            Http2HeadersFrame responseHeaders =
                RFCTestUtils.readIgnoringWindowUpdates(connection, Http2HeadersFrame.class);
            assertThat(responseHeaders.headers().get(":status"), equalTo("200"));
            Http2DataFrame responseData =
                RFCTestUtils.readIgnoringWindowUpdates(connection, Http2DataFrame.class);
            assertThat(responseData.toUTF8(), equalTo("done"));
            assertThat(responseCompletionThread.get(5, TimeUnit.SECONDS), startsWith("application-"));
            assertThat(completionThread.get(5, TimeUnit.SECONDS), startsWith("application-"));
        }
    }

    @Test
    void rejectedHandlerDispatchesHttp2AsyncCompletionToTheAsyncExecutor() throws Exception {
        var handlerExecutor = track(Executors.newSingleThreadExecutor(namedThreads("handler-")));
        var asyncExecutor = track(Executors.newSingleThreadExecutor(namedThreads("async-")));
        var suspendedHandle = new CompletableFuture<AsyncHandle>();
        var exceptionThread = new CompletableFuture<String>();
        var completionThread = new CompletableFuture<String>();
        server = httpServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .withHandlerExecutor(handlerExecutor)
            .withAsyncExecutor(asyncExecutor)
            .withExceptionHandler((request, response, exception) -> {
                exceptionThread.complete(Thread.currentThread().getName());
                response.status(500);
                response.write("handled");
                return true;
            })
            .addHandler((request, response) -> {
                AsyncHandle handle = request.handleAsync();
                handle.addResponseCompleteHandler(info ->
                    completionThread.complete(Thread.currentThread().getName())
                );
                suspendedHandle.complete(handle);
                handlerExecutor.shutdown();
                return true;
            })
            .start();

        try (var h2Client = new H2Client();
             var connection = h2Client.connectClearText(server)) {
            connection.handshake();
            connection.socket().setSoTimeout(2000);
            connection.writeFrame(new Http2HeadersFrame(
                1,
                true,
                RFCTestUtils.getHelloHeaders("http", server.uri().getPort())
            )).flush();
            suspendedHandle.get(5, TimeUnit.SECONDS).complete(new IOException("async failure"));

            Http2HeadersFrame responseHeaders =
                RFCTestUtils.readIgnoringWindowUpdates(connection, Http2HeadersFrame.class);
            assertThat(responseHeaders.headers().get(":status"), equalTo("500"));
            Http2DataFrame responseData =
                RFCTestUtils.readIgnoringWindowUpdates(connection, Http2DataFrame.class);
            assertThat(responseData.toUTF8(), equalTo("handled"));
            assertThat(exceptionThread.get(5, TimeUnit.SECONDS), startsWith("async-"));
            assertThat(completionThread.get(5, TimeUnit.SECONDS), startsWith("async-"));
        }
    }

    @Test
    void rejectedApplicationExecutorsAbortAnAcceptedHttp2AsyncStream() throws Exception {
        var sharedExecutor = track(Executors.newSingleThreadExecutor(namedThreads("application-")));
        var suspendedHandle = new CompletableFuture<AsyncHandle>();
        server = httpServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .withHandlerExecutor(sharedExecutor)
            .withAsyncExecutor(sharedExecutor)
            .addHandler((request, response) -> {
                suspendedHandle.complete(request.handleAsync());
                sharedExecutor.shutdown();
                return true;
            })
            .start();

        try (var h2Client = new H2Client();
             var connection = h2Client.connectClearText(server)) {
            connection.handshake();
            connection.socket().setSoTimeout(2000);
            connection.writeFrame(new Http2HeadersFrame(
                1,
                true,
                RFCTestUtils.getHelloHeaders("http", server.uri().getPort())
            )).flush();
            suspendedHandle.get(5, TimeUnit.SECONDS).complete();

            Http2ResetStreamFrame reset =
                RFCTestUtils.readIgnoringWindowUpdates(connection, Http2ResetStreamFrame.class);
            assertThat(reset.streamId(), is(1));
            assertThat(reset.errorCodeEnum(), is(Http2ErrorCode.INTERNAL_ERROR));
            assertEventually(() -> server.stats().completedRequests(), equalTo(1L));
        }
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
    void webSocketEventsUseTheHandlerExecutor() throws Exception {
        var handlerExecutor = track(Executors.newSingleThreadExecutor(namedThreads("handler-")));
        var connectionExecutor = track(Executors.newCachedThreadPool(namedThreads("connection-")));
        var connectedThread = new CompletableFuture<String>();
        var messageThread = new CompletableFuture<String>();
        var errorThread = new CompletableFuture<String>();
        server = httpServer()
            .withHandlerExecutor(handlerExecutor)
            .withConnectionExecutor(connectionExecutor)
            .addHandler(webSocketHandler((request, responseHeaders) -> new SimpleWebSocket() {
                @Override
                public void onConnect(MuWebSocketSession session) throws Exception {
                    super.onConnect(session);
                    connectedThread.complete(Thread.currentThread().getName());
                }

                @Override
                public void onText(String message) {
                    messageThread.complete(Thread.currentThread().getName());
                    throw new MuException("message failure");
                }

                @Override
                public void onBinary(ByteBuffer payload) {
                }

                @Override
                public void onError(Throwable cause) {
                    errorThread.complete(Thread.currentThread().getName());
                }
            }))
            .start();

        String websocketUrl = "ws" + server.uri().toString().substring(4);
        WebSocket webSocket = client.newWebSocket(
            request().url(websocketUrl).build(),
            new WebSocketListener() {
            }
        );
        try {
            assertThat(connectedThread.get(5, TimeUnit.SECONDS), startsWith("handler-"));
            assertThat(webSocket.send("hello"), is(true));
            assertThat(messageThread.get(5, TimeUnit.SECONDS), startsWith("handler-"));
            assertThat(errorThread.get(5, TimeUnit.SECONDS), startsWith("handler-"));
        } finally {
            webSocket.cancel();
        }
    }

    @Test
    void webSocketTimeoutWaitsForAnInFlightCallbackAndUsesTheHandlerExecutor() throws Exception {
        var handlerExecutor = track(Executors.newFixedThreadPool(2, namedThreads("handler-")));
        var connectionExecutor = track(Executors.newCachedThreadPool(namedThreads("connection-")));
        var maintenanceExecutor = track(Executors.newSingleThreadExecutor(namedThreads("maintenance-")));
        var textEntered = new CountDownLatch(1);
        var releaseText = new CountDownLatch(1);
        var timeoutThread = new CompletableFuture<String>();
        var clientDisconnected = new CompletableFuture<Void>();
        server = httpServer()
            .withHandlerExecutor(handlerExecutor)
            .withConnectionExecutor(connectionExecutor)
            .withConnectionMaintenanceExecutor(maintenanceExecutor)
            .withIdleTimeout(100, TimeUnit.MILLISECONDS)
            .addHandler(webSocketHandler((request, responseHeaders) -> new SimpleWebSocket() {
                @Override
                public void onText(String message) {
                    textEntered.countDown();
                    try {
                        releaseText.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }

                @Override
                public void onBinary(ByteBuffer payload) {
                }

                @Override
                public void onError(Throwable cause) {
                    if (cause instanceof TimeoutException) {
                        timeoutThread.complete(Thread.currentThread().getName());
                    }
                }
            })
                .withPingInterval(0, TimeUnit.MILLISECONDS)
                .withIdleReadTimeout(0, TimeUnit.MILLISECONDS))
            .start();

        String websocketUrl = "ws" + server.uri().toString().substring(4);
        WebSocket webSocket = client.newWebSocket(
            request().url(websocketUrl).build(),
            new WebSocketListener() {
                @Override
                public void onFailure(WebSocket webSocket, Throwable t, @Nullable Response response) {
                    clientDisconnected.complete(null);
                }
            }
        );
        try {
            assertThat(webSocket.send("hold"), is(true));
            assertThat(textEntered.await(5, TimeUnit.SECONDS), is(true));
            clientDisconnected.get(5, TimeUnit.SECONDS);
            assertThat(timeoutThread.isDone(), is(false));
        } finally {
            releaseText.countDown();
            webSocket.cancel();
        }
        assertThat(timeoutThread.get(5, TimeUnit.SECONDS), startsWith("handler-"));
    }

    @Test
    void webSocketShutdownCallbacksUseTheHandlerExecutor() throws Exception {
        var handlerExecutor = track(Executors.newSingleThreadExecutor(namedThreads("handler-")));
        var connectionExecutor = track(Executors.newCachedThreadPool(namedThreads("connection-")));
        var connected = new CountDownLatch(1);
        var shutdownThread = new CompletableFuture<String>();
        server = httpServer()
            .withHandlerExecutor(handlerExecutor)
            .withConnectionExecutor(connectionExecutor)
            .addHandler(webSocketHandler((request, responseHeaders) -> new SimpleWebSocket() {
                @Override
                public void onConnect(MuWebSocketSession session) throws Exception {
                    super.onConnect(session);
                    connected.countDown();
                }

                @Override
                public void onText(String message) {
                }

                @Override
                public void onBinary(ByteBuffer payload) {
                }

                @Override
                public void onServerShuttingDown() throws Exception {
                    shutdownThread.complete(Thread.currentThread().getName());
                    session().close(1001, "Going away");
                }
            }))
            .start();

        String websocketUrl = "ws" + server.uri().toString().substring(4);
        WebSocket webSocket = client.newWebSocket(
            request().url(websocketUrl).build(),
            new WebSocketListener() {
            }
        );
        try {
            assertThat(connected.await(5, TimeUnit.SECONDS), is(true));
            server.stop(2, TimeUnit.SECONDS);
            assertThat(shutdownThread.get(5, TimeUnit.SECONDS), startsWith("handler-"));
            server = null;
        } finally {
            webSocket.cancel();
        }
    }

    @Test
    void blockedWebSocketShutdownCallbackDoesNotBlockTheShutdownDeadline() throws Exception {
        var handlerExecutor = track(Executors.newSingleThreadExecutor(namedThreads("handler-")));
        var connectionExecutor = track(Executors.newCachedThreadPool(namedThreads("connection-")));
        var clientExecutor = track(Executors.newSingleThreadExecutor(namedThreads("client-")));
        var connected = new CountDownLatch(1);
        var shutdownEntered = new CountDownLatch(1);
        var releaseShutdown = new CountDownLatch(1);
        server = httpServer()
            .withHandlerExecutor(handlerExecutor)
            .withConnectionExecutor(connectionExecutor)
            .addHandler(webSocketHandler((request, responseHeaders) -> new SimpleWebSocket() {
                @Override
                public void onConnect(MuWebSocketSession session) throws Exception {
                    super.onConnect(session);
                    connected.countDown();
                }

                @Override
                public void onText(String message) {
                }

                @Override
                public void onBinary(ByteBuffer payload) {
                }

                @Override
                public void onServerShuttingDown() throws Exception {
                    shutdownEntered.countDown();
                    releaseShutdown.await();
                }
            }))
            .start();

        String websocketUrl = "ws" + server.uri().toString().substring(4);
        WebSocket webSocket = client.newWebSocket(
            request().url(websocketUrl).build(),
            new WebSocketListener() {
            }
        );
        try {
            assertThat(connected.await(5, TimeUnit.SECONDS), is(true));
            MuServer runningServer = Objects.requireNonNull(server);
            Future<Boolean> stopped = clientExecutor.submit(() ->
                runningServer.stop(100, TimeUnit.MILLISECONDS)
            );
            assertThat(shutdownEntered.await(5, TimeUnit.SECONDS), is(true));
            assertThat(stopped.get(2, TimeUnit.SECONDS), is(false));
            server = null;
        } finally {
            releaseShutdown.countDown();
            webSocket.cancel();
        }
    }

    @Test
    void sharedExecutorSerializesShutdownBehindAnInFlightWebSocketCallback() throws Exception {
        var sharedExecutor = track(Executors.newSingleThreadExecutor(namedThreads("shared-")));
        var clientExecutor = track(Executors.newSingleThreadExecutor(namedThreads("client-")));
        var textEntered = new CountDownLatch(1);
        var releaseText = new CountDownLatch(1);
        var shutdownCalled = new CompletableFuture<@Nullable Void>();
        var callbackActive = new AtomicBoolean();
        var callbacksOverlapped = new AtomicBoolean();
        server = httpServer()
            .withHandlerExecutor(sharedExecutor)
            .withConnectionExecutor(sharedExecutor)
            .addHandler(webSocketHandler((request, responseHeaders) -> new SimpleWebSocket() {
                @Override
                public void onText(String message) throws Exception {
                    callbackActive.set(true);
                    textEntered.countDown();
                    try {
                        releaseText.await();
                    } finally {
                        callbackActive.set(false);
                    }
                }

                @Override
                public void onBinary(ByteBuffer payload) {
                }

                @Override
                public void onServerShuttingDown() {
                    callbacksOverlapped.set(callbackActive.get());
                    shutdownCalled.complete(null);
                }
            }))
            .start();

        String websocketUrl = "ws" + server.uri().toString().substring(4);
        WebSocket webSocket = client.newWebSocket(
            request().url(websocketUrl).build(),
            new WebSocketListener() {
            }
        );
        try {
            assertThat(webSocket.send("hold"), is(true));
            assertThat(textEntered.await(5, TimeUnit.SECONDS), is(true));
            MuServer runningServer = Objects.requireNonNull(server);
            Future<Boolean> stopped = clientExecutor.submit(() ->
                runningServer.stop(2, TimeUnit.SECONDS)
            );
            assertThrows(TimeoutException.class, () ->
                shutdownCalled.get(100, TimeUnit.MILLISECONDS)
            );
            releaseText.countDown();
            shutdownCalled.get(5, TimeUnit.SECONDS);
            assertThat(callbacksOverlapped.get(), is(false));
            assertThat(stopped.get(5, TimeUnit.SECONDS), is(true));
            server = null;
        } finally {
            releaseText.countDown();
            webSocket.cancel();
        }
    }

    @Test
    void sharedExecutorDrainsAWriteFailureCaughtInsideAWebSocketCallback() throws Exception {
        var sharedExecutor = track(Executors.newSingleThreadExecutor(namedThreads("shared-")));
        var textEntered = new CountDownLatch(1);
        var attemptWrite = new CountDownLatch(1);
        var writeFailure = new CompletableFuture<IOException>();
        var errorCallback = new CompletableFuture<Throwable>();
        server = httpServer()
            .withHandlerExecutor(sharedExecutor)
            .withConnectionExecutor(sharedExecutor)
            .addHandler(webSocketHandler((request, responseHeaders) -> new SimpleWebSocket() {
                @Override
                public void onText(String message) throws Exception {
                    textEntered.countDown();
                    attemptWrite.await();
                    try {
                        session().sendBinary(ByteBuffer.allocate(1024 * 1024));
                    } catch (IOException e) {
                        writeFailure.complete(e);
                    }
                }

                @Override
                public void onBinary(ByteBuffer payload) {
                }

                @Override
                public void onError(Throwable cause) {
                    errorCallback.complete(cause);
                }
            }))
            .start();

        try (var client = Http1Client.connect(server)) {
            client.writeRequestLine(Method.GET, "/")
                .writeHeader("Upgrade", "websocket")
                .writeHeader("Connection", "Upgrade")
                .writeHeader("Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ==")
                .writeHeader("Sec-WebSocket-Version", "13")
                .flushHeaders();
            assertThat(client.readLine(), equalTo("HTTP/1.1 101 Switching Protocols"));
            client.readHeaders();

            // A masked "hold" frame. A zero mask is valid and leaves the payload unchanged.
            client.out().write(new byte[]{
                (byte) 0x81, (byte) 0x84, 0, 0, 0, 0, 'h', 'o', 'l', 'd'
            });
            client.out().flush();
            assertThat(textEntered.await(5, TimeUnit.SECONDS), is(true));
            client.abort();
            attemptWrite.countDown();

            IOException expected = writeFailure.get(5, TimeUnit.SECONDS);
            assertThat(errorCallback.get(5, TimeUnit.SECONDS), is(expected));
        } finally {
            attemptWrite.countDown();
        }
    }

    @Test
    void sharedConnectionAndHandlerExecutorDoesNotDeadlockWebSocketEvents() throws Exception {
        var sharedExecutor = track(Executors.newSingleThreadExecutor(namedThreads("shared-")));
        var callbackThread = new CompletableFuture<String>();
        var clientMessage = new CompletableFuture<String>();
        server = httpServer()
            .withHandlerExecutor(sharedExecutor)
            .withConnectionExecutor(sharedExecutor)
            .addHandler(webSocketHandler((request, responseHeaders) -> new SimpleWebSocket() {
                @Override
                public void onText(String message) throws IOException {
                    callbackThread.complete(Thread.currentThread().getName());
                    session().sendText(message);
                }

                @Override
                public void onBinary(ByteBuffer payload) {
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
        try {
            assertThat(webSocket.send("hello"), is(true));
            assertThat(clientMessage.get(5, TimeUnit.SECONDS), equalTo("hello"));
            assertThat(callbackThread.get(5, TimeUnit.SECONDS), startsWith("shared-"));
        } finally {
            webSocket.cancel();
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
