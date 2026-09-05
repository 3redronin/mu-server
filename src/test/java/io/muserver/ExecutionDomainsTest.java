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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import scaffolding.Http1Client;

import java.io.EOFException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
    void anAcceptedConnectionQueuedBeforeStopCannotStartAfterStop() throws Exception {
        var connectionExecutor = track(new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1),
            namedThreads("connection-")
        ));
        var handlerExecutor = track(Executors.newSingleThreadExecutor(namedThreads("handler-")));
        var blockerStarted = new CountDownLatch(1);
        var releaseBlocker = new CountDownLatch(1);
        var handledRequests = new AtomicInteger();
        Future<?> blocker = connectionExecutor.submit(() -> {
            blockerStarted.countDown();
            releaseBlocker.await();
            return null;
        });
        assertThat(blockerStarted.await(5, TimeUnit.SECONDS), is(true));

        server = io.muserver.TestExecutionResources.configure(httpServer(), connectionExecutor, null, null, null)
            .withHandlerExecutor(handlerExecutor)
            .addHandler((request, response) -> {
                handledRequests.incrementAndGet();
                response.write("late response");
                return true;
            })
            .start();

        try (var client = new Socket(server.uri().getHost(), server.uri().getPort())) {
            client.getOutputStream().write((
                "GET / HTTP/1.1\r\n"
                    + "Host: " + server.uri().getHost() + "\r\n"
                    + "Connection: close\r\n"
                    + "\r\n"
            ).getBytes(StandardCharsets.US_ASCII));
            client.getOutputStream().flush();

            assertEventually(
                () -> connectionExecutor.getQueue().size(),
                equalTo(1)
            );

            server.stop();
            server = null;
            client.setSoTimeout(1000);
            try {
                assertThat(client.getInputStream().read(), equalTo(-1));
            } catch (java.net.SocketException reset) {
                // Closing an accepted socket with unread input may send RST instead of FIN.
                assertThat(client.isConnected(), is(true));
            }

            releaseBlocker.countDown();
            blocker.get(5, TimeUnit.SECONDS);
            assertEventually(
                () -> connectionExecutor.getQueue().size(),
                equalTo(0)
            );
            assertThat(connectionExecutor.isShutdown(), is(true));

            assertThat(handledRequests.get(), equalTo(0));
        } finally {
            releaseBlocker.countDown();
        }
    }

    @Test
    void queuedHttp2RequestReportsClientCancellationBeforeTheWriterProcessesTheReset() throws Exception {
        var handlerExecutor = track(Executors.newSingleThreadExecutor(namedThreads("handler-")));
        var writerExecutor = track(Executors.newSingleThreadExecutor(namedThreads("h2-writer-")));
        var handlerBlocked = new CountDownLatch(1);
        var releaseHandler = new CountDownLatch(1);
        var writerBlocked = new CountDownLatch(1);
        var releaseWriter = new CountDownLatch(1);
        var handledRequests = new AtomicInteger();
        var stateAtCompletion = new CompletableFuture<ResponseState>();
        handlerExecutor.submit(() -> {
            handlerBlocked.countDown();
            releaseHandler.await();
            return null;
        });

        server = TestExecutionResources.configure(httpServer(), null, writerExecutor, null, null)
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .withHandlerExecutor(handlerExecutor)
            .addHandler((request, response) -> {
                handledRequests.incrementAndGet();
                response.write("Should not run");
                return true;
            })
            // Snapshot the state here: the response can change after the callback returns.
            .addResponseCompleteListener(info -> stateAtCompletion.complete(info.response().responseState()))
            .start();

        try (var client = new H2Client(); var connection = client.connectClearText(server)) {
            connection.socket().setSoTimeout(5000);
            connection.handshake();
            assertThat(handlerBlocked.await(5, TimeUnit.SECONDS), is(true));
            writerExecutor.submit(() -> {
                writerBlocked.countDown();
                releaseWriter.await();
                return null;
            });
            assertThat(writerBlocked.await(5, TimeUnit.SECONDS), is(true));

            var serverConnection = (Http2Connection) server.activeConnections().iterator().next();
            var headers = new FieldBlock();
            headers.set(":method", "GET");
            headers.set(":scheme", "http");
            headers.set(":authority", "localhost");
            headers.set(":path", "/");
            connection.writeFrame(new Http2HeadersFrame(1, true, headers))
                .writeFrame(new Http2ResetStreamFrame(1, Http2ErrorCode.CANCEL.code())).flush();
            assertEventually(() -> {
                Http2Stream stream = serverConnection.testProbe().streams().applicationStream(1);
                return stream != null && stream.resetWasInitiated();
            }, is(true));

            // Let application completion run while the coordinator still cannot apply the reset.
            releaseHandler.countDown();
            assertThat(stateAtCompletion.get(5, TimeUnit.SECONDS), is(ResponseState.CLIENT_CANCELLED));
            assertThat(handledRequests.get(), is(0));
        } finally {
            releaseHandler.countDown();
            releaseWriter.countDown();
        }
    }

    @Test
    void handlerRejectionAfterFinalGoAwayStillSendsTheAdmittedResponse() throws Exception {
        var submitting = new CountDownLatch(1);
        var reject = new CountDownLatch(1);
        var handler = track(new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new java.util.concurrent.SynchronousQueue<>()) {
            @Override public void execute(Runnable task) {
                submitting.countDown();
                try {
                    if (!reject.await(5, TimeUnit.SECONDS)) throw new AssertionError("Rejection not released");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new RejectedExecutionException("full");
            }
        });
        server = httpServer().withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .withHandlerExecutor(handler).start();
        try (var client = new H2Client(); var connection = client.connectClearText(server)) {
            connection.socket().setSoTimeout(5000);
            connection.handshake();
            var headers = new FieldBlock();
            headers.set(":method", "GET");
            headers.set(":scheme", "http");
            headers.set(":authority", "localhost");
            headers.set(":path", "/");
            connection.writeFrame(new Http2HeadersFrame(1, true, headers)).flush();
            assertThat(submitting.await(5, TimeUnit.SECONDS), is(true));
            ((Http2Connection) server.activeConnections().iterator().next()).initiateGracefulShutdown();
            connection.readLogicalFrame(Http2GoAway.class);
            connection.readLogicalFrame(Http2GoAway.class);
            reject.countDown();
            assertThat(connection.readLogicalFrame(Http2HeadersFrame.class).headers().get(":status"), is("503"));
            assertThat(connection.readLogicalFrame(Http2DataFrame.class).toUTF8(), is("503 Service Unavailable"));
        } finally {
            reject.countDown();
        }
    }

    @Test
    void http2IoRemainsResponsiveWhenTheHandlerExecutorIsSaturated() throws Exception {
        var handlerExecutor = track(new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1),
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
        Future<?> queuedBlocker = handlerExecutor.submit(() -> {
            try {
                releaseBlocker.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        server = io.muserver.TestExecutionResources.configure(httpServer(), connectionExecutor, null, null, null)
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .withHandlerExecutor(handlerExecutor)
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

            var connection =
                (Http2Connection) server.activeConnections().iterator().next();
            Http2StreamRegistry streamRegistry =
                connection.testProbe().streams();
            assertThat(
                streamRegistry.lookup(1).rejectedRequestBody(),
                is(true)
            );

            assertThat(rejectedRequest.isDone(), is(false));
            assertThat(rejectionThread.isDone(), is(false));

            byte[] secondPing = {7, 6, 5, 4, 3, 2, 1, 0};
            con.writeFrame(RFCTestUtils.utf8DataFrame(1, true, "discarded"))
                .writeFrame(new Http2Ping(false, secondPing))
                .flush();
            assertThat(con.readLogicalFrame(Http2Ping.class), equalTo(new Http2Ping(true, secondPing)));
            assertThat(
                streamRegistry.lookup(1).rejectedRequestBody(),
                is(false)
            );
            releaseRejectListener.countDown();

            releaseBlocker.countDown();
            blocker.get(5, TimeUnit.SECONDS);
            queuedBlocker.get(5, TimeUnit.SECONDS);

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
    void requestRejectionListenerFailureDoesNotSkipLaterListeners() throws Exception {
        var asyncExecutor = track(Executors.newSingleThreadExecutor(namedThreads("async-")));
        var firstListenerCalls = new AtomicInteger();
        var laterListenerThread = new CompletableFuture<String>();
        server = httpServer()
            .withMaxHeadersSize(1024)
            .withHandlerExecutor(asyncExecutor)
            .addRequestRejectListener(info -> {
                firstListenerCalls.incrementAndGet();
                throw new AssertionError("deliberate request rejection listener failure");
            })
            .addRequestRejectListener(info ->
                laterListenerThread.complete(Thread.currentThread().getName())
            )
            .start();

        try (var client = Http1Client.connect(server)) {
            client.writeRequestLine(Method.GET, "/")
                .writeHeader("x-big", "a".repeat(2000))
                .flushHeaders();
            assertThat(client.readLine(), startsWith("HTTP/1.1 431"));
            client.readBody(client.readHeaders());
        }

        assertThat(laterListenerThread.get(5, TimeUnit.SECONDS), startsWith("async-"));
        assertThat(firstListenerCalls.get(), is(1));
    }

    @Test
    void http1RejectionStillReachesTheWireWhenNotificationDispatchRejects() throws Exception {
        var rejectedAsyncExecutor =
            track(Executors.newSingleThreadExecutor(namedThreads("rejected-async-")));
        rejectedAsyncExecutor.shutdown();
        var clientExecutor = track(Executors.newSingleThreadExecutor(namedThreads("client-")));
        var listenerEntered = new CountDownLatch(1);
        var releaseListener = new CountDownLatch(1);
        server = httpServer()
            .withMaxHeadersSize(1024)
            .withHandlerExecutor(rejectedAsyncExecutor)
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
            Future<String> responseLine = clientExecutor.submit(client::readLine);
            assertThat(responseLine.get(2, TimeUnit.SECONDS), startsWith("HTTP/1.1 431"));
            assertThat(listenerEntered.getCount(), is(1L));
        } finally {
            releaseListener.countDown();
        }
    }

    @Test
    void singleThreadConnectionAndWriterExecutorsMakeIndependentProgress() throws Exception {
        var connectionExecutor = track(Executors.newSingleThreadExecutor(namedThreads("connection-")));
        var writerExecutor = track(Executors.newSingleThreadExecutor(namedThreads("h2-writer-")));

        server = io.muserver.TestExecutionResources.configure(httpServer(), connectionExecutor, writerExecutor, null, null)
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
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
        server = io.muserver.TestExecutionResources.configure(httpServer(), connectionExecutor, null, null, null)
            .withMaxHeadersSize(1024)
            .withHandlerExecutor(asyncExecutor)
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
    void detachedTaskTrackerSupportsLargeBatchesAndRepeatedDrains() {
        var tasks = new ApplicationTaskTracker();
        var registrations = new java.util.ArrayList<ApplicationTaskTracker.Registration>();
        for (int i = 0; i < 70_000; i++) {
            registrations.add(tasks.register());
        }
        assertThat(tasks.awaitUntil(System.nanoTime()), is(false));
        registrations.forEach(ApplicationTaskTracker.Registration::close);
        registrations.forEach(ApplicationTaskTracker.Registration::close);
        assertThat(tasks.awaitUntil(System.nanoTime()), is(true));
        try (var registration = tasks.register()) {
            assertThat(tasks.awaitUntil(System.nanoTime()), is(false));
        }
        assertThat(tasks.awaitUntil(System.nanoTime()), is(true));
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
    void requestRejectionListenerDoesNotWaitForCallbackQueuedBehindIt() throws Exception {
        var asyncExecutor = track(Executors.newSingleThreadExecutor(namedThreads("async-")));
        var blockerEntered = new CountDownLatch(1);
        var releaseBlocker = new CountDownLatch(1);
        asyncExecutor.execute(() -> {
            blockerEntered.countDown();
            try {
                releaseBlocker.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(blockerEntered.await(5, TimeUnit.SECONDS), is(true));

        var runningServer = new CompletableFuture<MuServer>();
        var stopResult = new CompletableFuture<Boolean>();
        var callbackNumber = new AtomicInteger();
        var secondCallbackRan = new CountDownLatch(1);
        server = httpServer()
            .withMaxHeadersSize(1024)
            .withHandlerExecutor(asyncExecutor)
            .addRequestRejectListener(info -> {
                if (callbackNumber.incrementAndGet() == 1) {
                    try {
                        stopResult.complete(
                            runningServer.get().stop(2, TimeUnit.SECONDS)
                        );
                    } catch (Throwable failure) {
                        stopResult.completeExceptionally(failure);
                    }
                } else {
                    secondCallbackRan.countDown();
                }
            })
            .start();
        runningServer.complete(server);

        try {
            for (int i = 0; i < 2; i++) {
                try (var client = Http1Client.connect(server)) {
                    client.writeRequestLine(Method.GET, "/")
                        .writeHeader("x-big", "a".repeat(2000))
                        .flushHeaders();
                    assertThat(client.readLine(), startsWith("HTTP/1.1 431"));
                }
            }

            releaseBlocker.countDown();
            assertThat(stopResult.get(1, TimeUnit.SECONDS), is(true));
            assertThat(secondCallbackRan.await(1, TimeUnit.SECONDS), is(true));
            server = null;
        } finally {
            releaseBlocker.countDown();
        }
    }

    @Test
    void externalStopStillWaitsAfterCallbackLocalStopReturns() throws Exception {
        var asyncExecutor = track(Executors.newSingleThreadExecutor(namedThreads("async-")));
        var clientExecutor = track(Executors.newSingleThreadExecutor(namedThreads("client-")));
        var runningServer = new CompletableFuture<MuServer>();
        var localStopResult = new CompletableFuture<Boolean>();
        var localStopReturned = new CountDownLatch(1);
        var releaseCallback = new CountDownLatch(1);
        server = httpServer()
            .withMaxHeadersSize(1024)
            .withHandlerExecutor(asyncExecutor)
            .addRequestRejectListener(info -> {
                try {
                    localStopResult.complete(
                        runningServer.get().stop(2, TimeUnit.SECONDS)
                    );
                    localStopReturned.countDown();
                    releaseCallback.await();
                } catch (Throwable failure) {
                    localStopResult.completeExceptionally(failure);
                }
            })
            .start();
        runningServer.complete(server);

        try {
            try (var client = Http1Client.connect(server)) {
                client.writeRequestLine(Method.GET, "/")
                    .writeHeader("x-big", "a".repeat(2000))
                    .flushHeaders();
                assertThat(client.readLine(), startsWith("HTTP/1.1 431"));
            }

            assertThat(localStopResult.get(1, TimeUnit.SECONDS), is(true));
            assertThat(localStopReturned.await(1, TimeUnit.SECONDS), is(true));

            Future<Boolean> externalStop = clientExecutor.submit(() ->
                runningServer.get().stop(2, TimeUnit.SECONDS)
            );
            assertThrows(
                TimeoutException.class,
                () -> externalStop.get(100, TimeUnit.MILLISECONDS)
            );
            releaseCallback.countDown();
            assertThat(externalStop.get(5, TimeUnit.SECONDS), is(true));
            server = null;
        } finally {
            releaseCallback.countDown();
        }
    }

    @Test
    void anIdleHttp1ConnectionDoesNotRetainAHandlerWorker() throws Exception {
        var handlerExecutor = track(Executors.newSingleThreadExecutor(namedThreads("handler-")));
        var connectionExecutor = track(Executors.newCachedThreadPool(namedThreads("connection-")));
        var clientExecutor = track(Executors.newSingleThreadExecutor(namedThreads("client-")));
        server = io.muserver.TestExecutionResources.configure(httpServer(), connectionExecutor, null, null, null)
            .withHandlerExecutor(handlerExecutor)
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
        server = io.muserver.TestExecutionResources.configure(httpServer(), null, null, null, null)
            .withHandlerExecutor(sharedExecutor)
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
        server = io.muserver.TestExecutionResources.configure(httpServer(), connectionExecutor, null, null, null)
            .withHandlerExecutor(handlerExecutor)
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
    void completionListenerRegistrationIsOrderedAgainstNotification() throws Exception {
        var handlerExecutor = track(Executors.newFixedThreadPool(2, namedThreads("handler-")));
        var connectionExecutor = track(Executors.newSingleThreadExecutor(namedThreads("connection-")));
        var responseRef = new CompletableFuture<MuResponse>();
        var firstListenerStarted = new CountDownLatch(1);
        var releaseFirstListener = new CountDownLatch(1);
        var firstListenerThread = new CompletableFuture<String>();
        var lateListenerThread = new CompletableFuture<String>();
        var serverListenerFinished = new CompletableFuture<Void>();
        server = io.muserver.TestExecutionResources.configure(httpServer(), connectionExecutor, null, null, null)
            .withHandlerExecutor(handlerExecutor)
            .addResponseCompleteListener(info -> serverListenerFinished.complete(null))
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.addCompletionListener(info -> {
                    firstListenerThread.complete(Thread.currentThread().getName());
                    firstListenerStarted.countDown();
                    try {
                        releaseFirstListener.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                responseRef.complete(response);
                response.write("done");
            })
            .start();

        try (var client = Http1Client.connect(server)) {
            client.writeRequestLine(Method.GET, "/").flushHeaders();
            assertThat(client.readLine(), equalTo("HTTP/1.1 200 OK"));
            assertThat(client.readBody(client.readHeaders()), equalTo("done"));
            assertThat(firstListenerStarted.await(5, TimeUnit.SECONDS), is(true));

            responseRef.get(5, TimeUnit.SECONDS).addCompletionListener(info ->
                lateListenerThread.complete(Thread.currentThread().getName())
            );
            releaseFirstListener.countDown();

            assertThat(
                lateListenerThread.get(5, TimeUnit.SECONDS),
                equalTo(firstListenerThread.get(5, TimeUnit.SECONDS))
            );
            serverListenerFinished.get(5, TimeUnit.SECONDS);

            var postCompletionThread = new CompletableFuture<String>();
            responseRef.get(5, TimeUnit.SECONDS).addCompletionListener(info ->
                postCompletionThread.complete(Thread.currentThread().getName())
            );
            assertThat(postCompletionThread.get(5, TimeUnit.SECONDS), startsWith("handler-"));
        } finally {
            releaseFirstListener.countDown();
        }
    }

    @Test
    void completionListenerFailureDoesNotStrandOtherListeners() throws Exception {
        var handlerExecutor = track(Executors.newSingleThreadExecutor(namedThreads("handler-")));
        var connectionExecutor = track(Executors.newSingleThreadExecutor(namedThreads("connection-")));
        var responseRef = new CompletableFuture<MuResponse>();
        var events = new CopyOnWriteArrayList<String>();
        var serverListenersFinished = new CompletableFuture<Void>();
        server = io.muserver.TestExecutionResources.configure(httpServer(), connectionExecutor, null, null, null)
            .withHandlerExecutor(handlerExecutor)
            .addResponseCompleteListener(info -> {
                events.add("server-failing");
                throw new AssertionError("deliberate server listener failure");
            })
            .addResponseCompleteListener(info -> {
                events.add("server-second");
                serverListenersFinished.complete(null);
            })
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.addCompletionListener(info -> {
                    events.add("response-failing");
                    response.addCompletionListener(nested ->
                        events.add("response-nested")
                    );
                    throw new AssertionError("deliberate response listener failure");
                });
                response.addCompletionListener(info ->
                    events.add("response-second")
                );
                responseRef.complete(response);
                response.write("done");
            })
            .start();

        try (var client = Http1Client.connect(server)) {
            client.writeRequestLine(Method.GET, "/").flushHeaders();
            assertThat(client.readLine(), equalTo("HTTP/1.1 200 OK"));
            assertThat(client.readBody(client.readHeaders()), equalTo("done"));
        }

        serverListenersFinished.get(5, TimeUnit.SECONDS);
        assertThat(events, equalTo(List.of(
            "response-failing",
            "response-second",
            "response-nested",
            "server-failing",
            "server-second"
        )));

        var postCompletionListenerFinished = new CompletableFuture<Void>();
        responseRef.get(5, TimeUnit.SECONDS).addCompletionListener(info ->
            postCompletionListenerFinished.complete(null)
        );
        postCompletionListenerFinished.get(5, TimeUnit.SECONDS);
    }

    @Test
    void aSlowHttp1CompletionListenerDoesNotDelayTheNextRequest() throws Exception {
        var handlerExecutor = track(Executors.newFixedThreadPool(2, namedThreads("handler-")));
        var connectionExecutor = track(Executors.newSingleThreadExecutor(namedThreads("connection-")));
        var clientExecutor = track(Executors.newSingleThreadExecutor(namedThreads("client-")));
        var firstCompletionStarted = new CountDownLatch(1);
        var releaseFirstCompletion = new CountDownLatch(1);
        server = io.muserver.TestExecutionResources.configure(httpServer(), connectionExecutor, null, null, null)
            .withHandlerExecutor(handlerExecutor)
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
        server = io.muserver.TestExecutionResources.configure(httpServer(), connectionExecutor, null, null, null)
            .withHandlerExecutor(handlerExecutor)
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
    void asyncCompletionIsATerminalGateForLaterWrites() throws Exception {
        var handlerExecutor = track(Executors.newSingleThreadExecutor(namedThreads("handler-")));
        var asyncExecutor = track(Executors.newSingleThreadExecutor(namedThreads("async-")));
        var blockerStarted = new CountDownLatch(1);
        var releaseBlocker = new CountDownLatch(1);
        Future<?> blocker = asyncExecutor.submit(() -> {
            blockerStarted.countDown();
            releaseBlocker.await();
            return null;
        });
        assertThat(blockerStarted.await(5, TimeUnit.SECONDS), is(true));

        var suspendedHandle = new CompletableFuture<AsyncHandle>();
        server = TestExecutionResources.configure(httpServer(),
            track(Executors.newCachedThreadPool()), null, asyncExecutor, null)
            .withHandlerExecutor(handlerExecutor)
            .addHandler((request, response) -> {
                suspendedHandle.complete(request.handleAsync());
                return true;
            })
            .start();

        try (var client = Http1Client.connect(server)) {
            client.writeRequestLine(Method.GET, "/").flushHeaders();
            AsyncHandle handle = suspendedHandle.get(5, TimeUnit.SECONDS);
            handlerExecutor.submit(() -> {
            }).get(5, TimeUnit.SECONDS);

            Future<@Nullable Void> acceptedWrite = handle.write(ByteBuffer.wrap(new byte[]{'a'}));
            handle.complete();
            Future<@Nullable Void> lateWrite = handle.write(ByteBuffer.wrap(new byte[]{1}));
            var callbackFailure = new CompletableFuture<Throwable>();
            var callbackThread = new CompletableFuture<String>();
            handle.write(ByteBuffer.wrap(new byte[]{2}), failure -> {
                callbackThread.complete(Thread.currentThread().getName());
                callbackFailure.complete(failure);
            });

            assertThat(acceptedWrite.isDone(), is(false));
            ExecutionException failedWrite = assertThrows(
                ExecutionException.class,
                () -> lateWrite.get(5, TimeUnit.SECONDS)
            );
            assertThat(failedWrite.getCause(), instanceOf(IllegalStateException.class));

            releaseBlocker.countDown();
            blocker.get(5, TimeUnit.SECONDS);
            acceptedWrite.get(5, TimeUnit.SECONDS);
            assertThat(callbackFailure.get(5, TimeUnit.SECONDS), instanceOf(IllegalStateException.class));
            assertThat(callbackThread.get(5, TimeUnit.SECONDS), startsWith("handler-"));
            assertThat(client.readLine(), equalTo("HTTP/1.1 200 OK"));
            assertThat(
                client.readHeaders().contains(HeaderNames.TRANSFER_ENCODING, HeaderValues.CHUNKED, true),
                is(true)
            );
            assertThat(client.readLine(), equalTo("1"));
            assertThat(client.in().read(), is((int) 'a'));
            assertThat(client.in().read(), is((int) '\r'));
            assertThat(client.in().read(), is((int) '\n'));
            assertThat(client.readLine(), equalTo("0"));
            assertThat(client.readLine(), equalTo(""));
        } finally {
            releaseBlocker.countDown();
            blocker.get(5, TimeUnit.SECONDS);
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
            .withHandlerExecutor(asyncExecutor)
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
    void asyncReadListenerClaimsBodyBeforeDispatch() throws Exception {
        var asyncExecutor = track(Executors.newSingleThreadExecutor(namedThreads("async-")));
        var blockerStarted = new CountDownLatch(1);
        var releaseBlocker = new CountDownLatch(1);
        Future<?> blocker = asyncExecutor.submit(() -> {
            blockerStarted.countDown();
            releaseBlocker.await();
            return null;
        });
        assertThat(blockerStarted.await(5, TimeUnit.SECONDS), is(true));

        var competingReadFailure = new CompletableFuture<Throwable>();
        server = TestExecutionResources.configure(httpServer(),
            track(Executors.newCachedThreadPool()), null, asyncExecutor, null)
            .addHandler(Method.POST, "/", (request, response, pathParams) -> {
                AsyncHandle handle = request.handleAsync();
                handle.setReadListener(new RequestBodyListener() {
                    @Override
                    public void onDataReceived(
                        ByteBuffer data,
                        DoneCallback doneCallback
                    ) throws Exception {
                        doneCallback.onComplete(null);
                    }

                    @Override
                    public void onComplete() {
                    }

                    @Override
                    public void onError(Throwable t) {
                    }
                });
                try {
                    request.readBodyAsString();
                    competingReadFailure.complete(null);
                } catch (Throwable failure) {
                    competingReadFailure.complete(failure);
                }
                handle.complete();
            })
            .start();

        try (Response response = call(
            request(server.uri()).post(RequestBody.create("body", null))
        )) {
            assertThat(response.code(), is(200));
            assertThat(
                competingReadFailure.get(5, TimeUnit.SECONDS),
                instanceOf(IllegalStateException.class)
            );
        } finally {
            releaseBlocker.countDown();
            blocker.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void asyncBodyListenerFailuresAreReportedOnTheAsyncExecutor() throws Exception {
        var asyncExecutor = track(Executors.newSingleThreadExecutor(namedThreads("async-")));
        var dataThread = new CompletableFuture<String>();
        var errorThread = new CompletableFuture<String>();
        var reportedFailure = new CompletableFuture<Throwable>();
        var completed = new AtomicBoolean();
        var listenerFailure = new IllegalStateException("deliberate request-body listener failure");
        server = httpServer()
            .withHandlerExecutor(asyncExecutor)
            .addHandler(Method.POST, "/", (request, response, pathParams) -> {
                AsyncHandle handle = request.handleAsync();
                handle.setReadListener(new RequestBodyListener() {
                    @Override
                    public void onDataReceived(ByteBuffer data, DoneCallback doneCallback) {
                        dataThread.complete(Thread.currentThread().getName());
                        throw listenerFailure;
                    }

                    @Override
                    public void onComplete() {
                        completed.set(true);
                    }

                    @Override
                    public void onError(Throwable failure) {
                        errorThread.complete(Thread.currentThread().getName());
                        reportedFailure.complete(failure);
                    }
                });
            })
            .start();

        RequestBody body = new RequestBody() {
            @Override
            public @Nullable MediaType contentType() {
                return null;
            }

            @Override
            public void writeTo(BufferedSink sink) throws IOException {
                sink.writeUtf8("body");
            }
        };
        try (Response response = call(request(server.uri()).post(body))) {
            assertThat(response.code(), is(500));
        }

        assertThat(reportedFailure.get(5, TimeUnit.SECONDS), is(listenerFailure));
        assertThat(dataThread.get(5, TimeUnit.SECONDS), startsWith("async-"));
        assertThat(errorThread.get(5, TimeUnit.SECONDS), startsWith("async-"));
        assertThat(completed.get(), is(false));
    }

    @Test
    void oneQueuedApplicationWorkerCanReadAndEchoARequestBody() throws Exception {
        var applicationExecutor = track(Executors.newSingleThreadExecutor(namedThreads("application-")));
        var callbackThreads = new CopyOnWriteArrayList<String>();
        byte[] payload = new byte[20_000];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) ('a' + (i % 26));
        }
        server = httpServer()
            .withHandlerExecutor(applicationExecutor)
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
            assertThat(response.code(), is(200));
            assertThat(response.body().bytes(), equalTo(payload));
        }
        assertThat(callbackThreads.size(), greaterThanOrEqualTo(3));
        for (String callbackThread : callbackThreads) {
            assertThat(callbackThread, startsWith("application-"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectedAsyncCallbackTerminatesTheRequestAndReleasesAdmission(boolean outputStarted) throws Exception {
        var rejectNext = new AtomicBoolean();
        var rejection = new CompletableFuture<RejectedExecutionException>();
        var callbackInvoked = new AtomicBoolean();
        var completed = new CompletableFuture<ResponseState>();
        var applicationExecutor = track(new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new java.util.concurrent.LinkedBlockingQueue<>(), namedThreads("application-")) {
            @Override public void execute(Runnable task) {
                if (rejectNext.compareAndSet(true, false)) {
                    var failure = new RejectedExecutionException("deliberate callback rejection");
                    rejection.complete(failure);
                    throw failure;
                }
                super.execute(task);
            }
        });
        server = httpServer().withHandlerExecutor(applicationExecutor).withMaxConcurrentRequests(1)
            .addResponseCompleteListener(info -> {
                if (info.request().relativePath().equals("/")) {
                    completed.complete(info.response().responseState());
                }
            })
            .addHandler((request, response) -> {
                if (request.relativePath().equals("/next")) {
                    response.write("next");
                    return true;
                }
                AsyncHandle handle = request.handleAsync();
                rejectNext.set(true);
                if (outputStarted) {
                    handle.write(ByteBuffer.wrap(new byte[]{1, 2, 3}), error -> {
                        callbackInvoked.set(true);
                        handle.complete(error);
                    });
                } else {
                    handle.setReadListener(new RequestBodyListener() {
                        @Override public void onDataReceived(ByteBuffer data, DoneCallback done) throws Exception {
                            callbackInvoked.set(true);
                            done.onComplete(null);
                        }
                        @Override public void onComplete() { handle.complete(); }
                        @Override public void onError(Throwable failure) { handle.complete(failure); }
                    });
                }
                return true;
            }).start();

        var firstRequest = request(server.uri());
        if (!outputStarted) firstRequest.post(RequestBody.create(new byte[]{1}, null));
        try (Response response = call(firstRequest)) {
            if (outputStarted) {
                assertThat(response.code(), is(200));
                // The body was started, so failure must close it without a successful chunk terminator.
                assertThrows(IOException.class, () -> response.body().bytes());
            } else {
                assertThat(response.code(), is(500));
            }
        }
        assertThat(rejection.get(5, TimeUnit.SECONDS).getMessage(), is("deliberate callback rejection"));
        assertThat(completed.get(5, TimeUnit.SECONDS).endState(), is(true));
        assertThat(callbackInvoked.get(), is(false));
        try (Response next = call(request(server.uri().resolve("/next")))) {
            assertThat(next.code(), is(200));
            assertThat(next.body().string(), is("next"));
        }
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
    void rejectedApplicationExecutorsAbortAnAcceptedHttp2AsyncStream() throws Exception {
        var sharedExecutor = track(Executors.newSingleThreadExecutor(namedThreads("application-")));
        var suspendedHandle = new CompletableFuture<AsyncHandle>();
        server = httpServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .withHandlerExecutor(sharedExecutor)
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
            AsyncHandle handle = suspendedHandle.get(5, TimeUnit.SECONDS);
            assertThat(sharedExecutor.awaitTermination(5, TimeUnit.SECONDS), is(true));
            handle.complete();

            Http2ResetStreamFrame reset =
                RFCTestUtils.readIgnoringWindowUpdates(connection, Http2ResetStreamFrame.class);
            assertThat(reset.streamId(), is(1));
            assertThat(reset.errorCodeEnum(), is(Http2ErrorCode.INTERNAL_ERROR));
            assertEventually(() -> server.stats().completedRequests(), equalTo(1L));
        }
    }

    @SuppressWarnings("deprecation")
    @Test
    void deprecatedWebSocketAdaptersUseOneApplicationWorkerWithoutSelfDeadlock() throws Exception {
        var asyncExecutor = track(Executors.newSingleThreadExecutor(namedThreads("async-")));
        var callbackThreads = new CopyOnWriteArrayList<String>();
        var clientMessage = new CompletableFuture<String>();
        server = httpServer()
            .withHandlerExecutor(asyncExecutor)
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
        assertEventually(callbackThreads::size, is(2));
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
        server = io.muserver.TestExecutionResources.configure(httpServer(), connectionExecutor, null, null, null)
            .withHandlerExecutor(handlerExecutor)
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
        server = io.muserver.TestExecutionResources.configure(httpServer(), connectionExecutor, null, maintenanceExecutor, null)
            .withHandlerExecutor(handlerExecutor)
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
        server = io.muserver.TestExecutionResources.configure(httpServer(), connectionExecutor, null, null, null)
            .withHandlerExecutor(handlerExecutor)
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
        server = io.muserver.TestExecutionResources.configure(httpServer(), connectionExecutor, null, null, null)
            .withHandlerExecutor(handlerExecutor)
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
    void applicationFailureDuringWebSocketShutdownStillReachesOnError() throws Exception {
        var handler = track(Executors.newSingleThreadExecutor(namedThreads("handler-")));
        var session = new CompletableFuture<WebsocketConnection>();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var observed = new CompletableFuture<Throwable>();
        var expected = new IOException("application callback failed during shutdown");
        server = httpServer().withHandlerExecutor(handler)
            .addHandler(webSocketHandler((request, responseHeaders) -> new SimpleWebSocket() {
                @Override public void onConnect(MuWebSocketSession connected) throws Exception {
                    super.onConnect(connected);
                    session.complete((WebsocketConnection) connected);
                }
                @Override public void onText(String message) throws Exception {
                    entered.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Callback not released");
                    throw expected;
                }
                @Override public void onBinary(ByteBuffer payload) { }
                @Override public void onServerShuttingDown() { }
                @Override public void onError(Throwable cause) { observed.complete(cause); }
            })).start();
        WebSocket socket = client.newWebSocket(
            request().url("ws" + server.uri().toString().substring(4)).build(), new WebSocketListener() { });
        try {
            WebsocketConnection connection = session.get(5, TimeUnit.SECONDS);
            socket.send("fail");
            assertThat(entered.await(5, TimeUnit.SECONDS), is(true));
            connection.onServerShuttingDown();
            release.countDown();
            assertThat(observed.get(5, TimeUnit.SECONDS), is(expected));
        } finally {
            release.countDown();
            socket.cancel();
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
        server = io.muserver.TestExecutionResources.configure(httpServer(), null, null, null, null)
            .withHandlerExecutor(sharedExecutor)
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
                public void onServerShuttingDown() throws Exception {
                    callbacksOverlapped.set(callbackActive.get());
                    shutdownCalled.complete(null);
                    super.onServerShuttingDown();
                }
            }))
            .start();

        String websocketUrl = "ws" + server.uri().toString().substring(4);
        WebSocket webSocket = client.newWebSocket(
            request().url(websocketUrl).build(),
            new WebSocketListener() {
                @Override public void onClosing(WebSocket socket, int code, String reason) {
                    socket.close(code, reason);
                }
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
        server = io.muserver.TestExecutionResources.configure(httpServer(), null, null, null, null)
            .withHandlerExecutor(sharedExecutor)
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
        server = io.muserver.TestExecutionResources.configure(httpServer(), null, null, null, null)
            .withHandlerExecutor(sharedExecutor)
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

        server = io.muserver.TestExecutionResources.configure(httpServer(), connectionExecutor, writerExecutor, null, null)
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
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

        server = io.muserver.TestExecutionResources.configure(httpServer(), connectionExecutor, writerExecutor, maintenanceExecutor, null)
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
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

        var builder = io.muserver.TestExecutionResources.configure(httpServer(), connectionExecutor, writerExecutor, maintenanceExecutor, timerExecutor)
            .withHandlerExecutor(handlerExecutor)
            .addHandler(Method.GET, "/", (request, response, pathParams) ->
                response.write(Thread.currentThread().getName()));

        server = builder.start();
        try (Response response = call(request(server.uri()))) {
            assertThat(response.code(), equalTo(200));
            assertThat(response.body().string(), startsWith("handler-"));
        }

        server.stop();
        server = null;
        assertThat(connectionExecutor.isShutdown(), is(true));
        assertThat(writerExecutor.isShutdown(), is(true));
        assertThat(maintenanceExecutor.isShutdown(), is(true));
        assertThat(handlerExecutor.isShutdown(), is(false));
        assertThat(asyncExecutor.isShutdown(), is(false));
        assertThat(timerExecutor.isShutdown(), is(true));
    }

    @Test
    void serverOwnedExecutorsAreShutDownWithTheServer() throws Exception {
        var builder = httpServer();
        var created = new java.util.concurrent.atomic.AtomicReference<ExecutionResources>();
        builder.executionResourcesFactory = application -> {
            ExecutionResources resources = ExecutionResources.create(application);
            created.set(resources);
            return resources;
        };
        server = builder.start();
        ExecutionResources resources = created.get();
        List<ExecutorService> serverOwnedExecutors = List.of(resources.application, resources.internal, resources.timer);

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

        assertThrows(RejectedExecutionException.class, () -> io.muserver.TestExecutionResources.configure(httpServer(), connectionExecutor, writerExecutor, maintenanceExecutor, timerExecutor)
            .withHttpPort(port)
            .withHandlerExecutor(handlerExecutor)
            .start());

        assertThat(handlerExecutor.isShutdown(), is(false));
        assertThat(asyncExecutor.isShutdown(), is(false));
        assertThat(connectionExecutor.isShutdown(), is(true));
        assertThat(writerExecutor.isShutdown(), is(true));
        assertThat(maintenanceExecutor.isShutdown(), is(true));
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
            assertThrows(MuException.class, () -> io.muserver.TestExecutionResources.configure(MuServerBuilder.muServer(), connectionExecutor, writerExecutor, maintenanceExecutor, timerExecutor)
                .withHttpsPort(firstPort)
                .withHttpPort(occupiedListener.getLocalPort())
                .withHandlerExecutor(handlerExecutor)
                .start());
        }

        assertThat(handlerExecutor.isShutdown(), is(false));
        assertThat(asyncExecutor.isShutdown(), is(false));
        assertThat(connectionExecutor.isShutdown(), is(true));
        assertThat(writerExecutor.isShutdown(), is(true));
        assertThat(maintenanceExecutor.isShutdown(), is(true));
        assertThat(timerExecutor.isShutdown(), is(true));
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

        server = io.muserver.TestExecutionResources.configure(httpServer(), connectionExecutor, null, maintenanceExecutor, null)
            .withHandlerExecutor(handlerExecutor)
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
