package io.muserver;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;

import static io.muserver.MuServerBuilder.httpServer;
import static io.muserver.MuServerBuilder.httpsServer;
import static io.muserver.RFCTestUtils.goAway;
import static io.muserver.RFCTestUtils.readIgnoringWindowUpdates;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.empty;
import static scaffolding.MuAssert.assertEventually;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("RFC 9113 5.1 Stream States")
class RFC9113_5_1_StreamStatesTest {

    private @Nullable MuServer server;

    @Test
    void localEndStreamStopsCountingBeforeFlushReturns() throws Exception {
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            con.handshake();
            var liveConnection =
                (Http2Connection) server.activeConnections().iterator().next();
            var executor = Executors.newSingleThreadExecutor();
            try {
                var writerConnection = new Http2Connection(
                    liveConnection.server,
                    liveConnection.creator,
                    liveConnection.clientSocket,
                    liveConnection.clientCertificate,
                    Instant.now(),
                    Http2Settings.DEFAULT_CLIENT_SETTINGS,
                    5000,
                    executor,
                    executor
                );
                var requestUri = server.uri().resolve("/synthetic");
                var request = new Mu3Request(
                    writerConnection,
                    Method.GET,
                    requestUri,
                    server.uri(),
                    HttpVersion.HTTP_2,
                    new FieldBlock(),
                    BodySize.NONE,
                    EmptyInputStream.INSTANCE
                );
                var stream = new Http2Stream(
                    1,
                    writerConnection,
                    Http2StreamState.HALF_CLOSED_REMOTE,
                    request,
                    EmptyInputStream.INSTANCE
                );
                Http2WriteCoordinator coordinator = getField(
                    writerConnection,
                    "writeCoordinator",
                    Http2WriteCoordinator.class
                );
                coordinator.openStream(
                    1,
                    65_535,
                    Http2StreamState.HALF_CLOSED_REMOTE,
                    stream
                );
                writerConnection.write(
                    new Http2HeadersFrame(1, true, new FieldBlock())
                );

                assertThat(stream.countsTowardsMaxConcurrentStreams(), equalTo(true));
                var flushEntered = new CountDownLatch(1);
                var countedDuringFlush = new AtomicReference<Boolean>();
                var output = new ByteArrayOutputStream() {
                    @Override
                    public void flush() {
                        countedDuringFlush.set(
                            stream.countsTowardsMaxConcurrentStreams()
                        );
                        flushEntered.countDown();
                    }
                };

                writerConnection.startWriteLoop(output);
                assertThat(flushEntered.await(5, TimeUnit.SECONDS), equalTo(true));
                assertThat(countedDuringFlush.get(), equalTo(false));
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    public void streamIDsFromClientsCannotBeRepeated() throws Exception {

        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> {
                response.status(202);
            })
            .start();

        try (var client = new H2Client();
             var con = client.connect(server.uri().getPort())) {

            con.handshake();

            FieldBlock headers = getHelloHeaders();
            con.writeFrame(new Http2HeadersFrame(1, true, headers));
            con.flush();

            var respHeaders = con.readLogicalFrame(Http2HeadersFrame.class);
            assertThat(respHeaders.headers().get(":status"), equalTo("202"));

            // reuse the same stream ID
            con.writeFrame(new Http2HeadersFrame(1, true, headers));
            con.flush();

            var goaway = con.readLogicalFrame(Http2GoAway.class);
            assertThat(goaway.errorCodeEnum(), equalTo(Http2ErrorCode.PROTOCOL_ERROR));

            assertThrows(IOException.class, con::readFrameHeader);
        }

    }

    @Test
    public void streamIDsCanBeSkipped() throws Exception {

        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> {
                response.status(202);
            })
            .start();

        try (var client = new H2Client();
             var con = client.connect(server.uri().getPort())) {

            con.handshake();

            con.writeFrame(new Http2HeadersFrame(1, true, getHelloHeaders())).flush();

            Http2HeadersFrame stream1 = con.readLogicalFrame(Http2HeadersFrame.class);
            assertThat(stream1.streamId(), equalTo(1));
            assertThat(stream1.headers().get(":status"), equalTo("202"));

            con.writeFrame(new Http2HeadersFrame(5, true, getHelloHeaders())).flush();

            Http2HeadersFrame stream2 = con.readLogicalFrame(Http2HeadersFrame.class);
            assertThat(stream2.streamId(), equalTo(5));
            assertThat(stream2.headers().get(":status"), equalTo("202"));

            con.writeFrame(goAway(3, Http2ErrorCode.NO_ERROR)).flush();

            assertThat(con.readLogicalFrame(Http2GoAway.class), equalTo(goAway(5, Http2ErrorCode.NO_ERROR)));
            assertThrows(IOException.class, con::readFrameHeader);
        }

    }


    @Test
    public void streamIDsCannotBeZero() throws Exception {

        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .start();

        try (var client = new H2Client();
             var con = client.connect(server.uri().getPort())) {

            con.handshake();

            con.writeFrame(new Http2HeadersFrame(0, true, getHelloHeaders()));
            con.flush();

            var goaway = con.readLogicalFrame(Http2GoAway.class);
            assertThat(goaway.errorCodeEnum(), equalTo(Http2ErrorCode.PROTOCOL_ERROR));
            assertThrows(IOException.class, con::readFrameHeader);
        }

    }

    @Test
    public void streamIDsCannotBeEven() throws Exception {

        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .start();

        try (var client = new H2Client();
             var con = client.connect(server.uri().getPort())) {

            con.handshake();

            con.writeFrame(new Http2HeadersFrame(2, true, getHelloHeaders()));
            con.flush();

            var goaway = con.readLogicalFrame(Http2GoAway.class);
            assertThat(goaway.errorCodeEnum(), equalTo(Http2ErrorCode.PROTOCOL_ERROR));
            assertThrows(IOException.class, con::readFrameHeader);
        }

    }

    @Test
    public void additionalHeadersOnTheMaximumHalfClosedRemoteStreamAreAStreamError() throws Exception {
        var handlerStarted = new CountDownLatch(1);
        var releaseHandler = new CountDownLatch(1);

        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> {
                handlerStarted.countDown();
                releaseHandler.await(5, TimeUnit.SECONDS);
                response.status(202);
            })
            .start();

        try {
            try (var client = new H2Client();
                 var con = client.connect(server.uri().getPort())) {

                con.handshake();

                con.writeFrame(new Http2HeadersFrame(Integer.MAX_VALUE, true, getHelloHeaders()));
                con.flush();

                assertThat(handlerStarted.await(5, TimeUnit.SECONDS), equalTo(true));

                // The high bit is reserved and ignored, so this is another HEADERS frame for
                // stream 2^31-1, which is known to be half-closed (remote) while the handler waits.
                con.writeFrame(new Http2HeadersFrame(0xFFFFFFFF, true, getHelloHeaders()));
                con.flush();

                var reset = con.readLogicalFrame(Http2ResetStreamFrame.class);
                assertThat(reset.streamId(), equalTo(Integer.MAX_VALUE));
                assertThat(reset.errorCodeEnum(), equalTo(Http2ErrorCode.STREAM_CLOSED));

                byte[] opaqueData = new byte[] {1, 2, 3, 4, 5, 6, 7, 8};
                con.writeFrame(new Http2Ping(false, opaqueData)).flush();
                assertThat(con.readLogicalFrame(Http2Ping.class), equalTo(new Http2Ping(true, opaqueData)));
            }
        } finally {
            releaseHandler.countDown();
        }
    }

    @Test
    public void headersAfterAClientResetCauseAStreamClosedError() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.POST, "/hello", (request, response, pathParams) -> {
                started.countDown();
                release.await(5, TimeUnit.SECONDS);
                response.status(204);
            })
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            con.handshake()
                .writeFrame(new Http2HeadersFrame(1, false, RFCTestUtils.postHelloHeaders(server.uri().getPort())))
                .flush();

            assertThat(started.await(5, TimeUnit.SECONDS), equalTo(true));

            con.writeFrame(new Http2ResetStreamFrame(1, Http2ErrorCode.CANCEL.code()))
                .writeFrame(new Http2HeadersFrame(1, true, getHelloHeaders()))
                .flush();

            var reset = con.readLogicalFrame(Http2ResetStreamFrame.class);
            assertThat(reset.streamId(), equalTo(1));
            assertThat(reset.errorCodeEnum(), equalTo(Http2ErrorCode.STREAM_CLOSED));
        } finally {
            release.countDown();
        }
    }

    @Test
    public void headersOnHistoricallyClosedStreamsCurrentlyReturnProtocolError() throws Exception {

        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> {
                response.status(202);
            })
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            con.handshake();

            con.writeFrame(new Http2HeadersFrame(1, true, getHelloHeaders()));
            con.writeFrame(new Http2HeadersFrame(3, true, getHelloHeaders()));
            con.flush();

            var respHeaders = con.readLogicalFrame(Http2HeadersFrame.class);
            assertThat(respHeaders.headers().get(":status"), equalTo("202"));
            var nextHeaders = con.readLogicalFrame(Http2HeadersFrame.class);
            assertThat(nextHeaders.headers().get(":status"), equalTo("202"));

            con.writeFrame(new Http2HeadersFrame(1, true, getHelloHeaders()));
            con.flush();

            var goaway = con.readLogicalFrame(Http2GoAway.class);
            // Spec-wise, this is more precisely a STREAM_CLOSED case because stream 1 previously existed
            // and was then closed. We intentionally assert PROTOCOL_ERROR instead because this server
            // does not retain historical closed-stream IDs after cleanup, avoiding per-connection memory
            // growth for bookkeeping on misbehaving clients.
            assertThat(goaway.errorCodeEnum(), equalTo(Http2ErrorCode.PROTOCOL_ERROR));

            assertThrows(IOException.class, con::readFrameHeader);
        }

    }

    @Test
    public void whenMaxConcurrentStreamsIsExceededStreamIsRefused() throws Exception {

        var okayLatch = new CountDownLatch(1);

        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder
                .http2Enabled()
                .withMaxConcurrentStreams(2)
            )
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> {
                response.status(202);
                okayLatch.await(1, TimeUnit.MINUTES);
            })
            .start();

        try (var client = new H2Client();
             var con = client.connect(server.uri().getPort())) {

            con.handshake();

            // first two work, but don't return headers yet as they are waiting on the latch
            con.writeFrame(new Http2HeadersFrame(1, true, getHelloHeaders()));
            con.writeFrame(new Http2HeadersFrame(3, true, getHelloHeaders()));

            // third one fails because max concurrent exceeded
            con.writeFrame(new Http2HeadersFrame(5, true, getHelloHeaders()));
            con.flush();

            var reset = con.readLogicalFrame(Http2ResetStreamFrame.class);
            assertThat(reset.streamId(), equalTo(5));
            assertThat(reset.errorCodeEnum(), equalTo(Http2ErrorCode.REFUSED_STREAM));

            // finish the first two
            okayLatch.countDown();

            // they may come back in any order
            assertThat(List.of(
                con.readLogicalFrame(Http2HeadersFrame.class).streamId(),
                con.readLogicalFrame(Http2HeadersFrame.class).streamId()),
                containsInAnyOrder(1, 3));

            // now can re-write the third one
            con.writeFrame(new Http2HeadersFrame(5, true, getHelloHeaders()))
                .flush();

            assertThat(con.readLogicalFrame(Http2HeadersFrame.class).streamId(), equalTo(5));

            con.writeFrame(goAway(5, Http2ErrorCode.NO_ERROR)).flush();
        }

    }

    @Test
    public void halfClosedLocalStreamsCountTowardsMaxConcurrentStreamsAfterTheHandlerEnds() throws Exception {
        var exchangeCompleted = new CountDownLatch(1);

        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder
                .http2Enabled()
                .withMaxConcurrentStreams(1)
            )
            .addResponseCompleteListener(info -> exchangeCompleted.countDown())
            .addHandler(Method.POST, "/hello", (request, response, pathParams) -> {
                response.status(204);
            })
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> {
                response.status(202);
            })
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            var requestHeaders = RFCTestUtils.postHelloHeaders(server.uri().getPort());
            requestHeaders.add("content-length", "0");
            con.handshake()
                .writeFrame(new Http2HeadersFrame(1, false, requestHeaders))
                .flush();

            var earlyResponse = con.readLogicalFrame(Http2HeadersFrame.class);
            assertThat(earlyResponse.streamId(), equalTo(1));
            assertThat(earlyResponse.headers().get(":status"), equalTo("204"));
            assertThat(exchangeCompleted.await(5, TimeUnit.SECONDS), equalTo(true));
            assertThat(server.stats().activeRequests().size(), equalTo(0));

            con.writeFrame(new Http2HeadersFrame(3, true, getHelloHeaders()))
                .flush();

            var refused = con.readLogicalFrame(Http2ResetStreamFrame.class);
            assertThat(refused.streamId(), equalTo(3));
            assertThat(refused.errorCodeEnum(), equalTo(Http2ErrorCode.REFUSED_STREAM));

            byte[] opaqueData = new byte[] {1, 2, 3, 4, 5, 6, 7, 8};
            con.writeFrame(Http2DataFrame.eos(1))
                .writeFrame(new Http2Ping(false, opaqueData))
                .flush();
            assertThat(con.readLogicalFrame(Http2Ping.class), equalTo(new Http2Ping(true, opaqueData)));

            con.writeFrame(new Http2HeadersFrame(5, true, getHelloHeaders()))
                .flush();
            var accepted = con.readLogicalFrame(Http2HeadersFrame.class);
            assertThat(accepted.streamId(), equalTo(5));
            assertThat(accepted.headers().get(":status"), equalTo("202"));
        }
    }

    @Test
    public void readerObservedEndStreamReleasesMaxConcurrentStreamSlotWithoutACoordinatorBarrier() throws Exception {
        readerObservedClosureReleasesMaxConcurrentStreamSlot(Http2DataFrame.eos(1));
    }

    @Test
    public void readerObservedResetReleasesMaxConcurrentStreamSlotWithoutACoordinatorBarrier() throws Exception {
        readerObservedClosureReleasesMaxConcurrentStreamSlot(
            new Http2ResetStreamFrame(1, Http2ErrorCode.CANCEL.code())
        );
    }

    private void readerObservedClosureReleasesMaxConcurrentStreamSlot(
        LogicalHttp2Frame terminalFrame
    ) throws Exception {
        var exchangeCompleted = new CountDownLatch(1);
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder
                .http2Enabled()
                .withMaxConcurrentStreams(1)
            )
            .addResponseCompleteListener(info -> exchangeCompleted.countDown())
            .addHandler(Method.POST, "/hello", (request, response, pathParams) -> {
                response.status(204);
            })
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> {
                response.status(202);
            })
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {
            con.handshake()
                .writeFrame(new Http2HeadersFrame(
                    1,
                    false,
                    RFCTestUtils.postHelloHeaders(server.uri().getPort())
                ))
                .flush();

            var earlyResponse = con.readLogicalFrame(Http2HeadersFrame.class);
            assertThat(earlyResponse.streamId(), equalTo(1));
            assertThat(earlyResponse.headers().get(":status"), equalTo("204"));
            assertThat(exchangeCompleted.await(5, TimeUnit.SECONDS), equalTo(true));

            var connection = (Http2Connection) server.activeConnections().iterator().next();
            Http2StreamRegistry streamRegistry =
                getField(connection, "streamRegistry", Http2StreamRegistry.class);
            var stream = Objects.requireNonNull(streamRegistry.applicationStream(1));
            Lock stateLock = getField(connection, "stateLock", Lock.class);
            stateLock.lock();
            try {
                con.writeFrame(terminalFrame)
                    .writeFrame(new Http2HeadersFrame(3, true, getHelloHeaders()))
                    .flush();

                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (stream.canReceiveData() && System.nanoTime() < deadline) {
                    Thread.sleep(1);
                }
                assertThat("The reader did not observe stream closure", stream.canReceiveData(), equalTo(false));
                assertThat(stream.countsTowardsMaxConcurrentStreams(), equalTo(false));
            } finally {
                stateLock.unlock();
            }

            var accepted = con.readLogicalFrame(Http2HeadersFrame.class);
            assertThat(accepted.streamId(), equalTo(3));
            assertThat(accepted.headers().get(":status"), equalTo("202"));
        }
    }

    @Test
    public void closedStreamsDoNotCountTowardsMaxConcurrentStreamsWhileTheirHandlerFinishes() throws Exception {
        var handlerStarted = new CountDownLatch(1);
        var releaseHandler = new CountDownLatch(1);

        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder
                .http2Enabled()
                .withMaxConcurrentStreams(1)
            )
            .addHandler(Method.POST, "/hello", (request, response, pathParams) -> {
                handlerStarted.countDown();
                releaseHandler.await(5, TimeUnit.SECONDS);
            })
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> {
                response.status(202);
            })
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            con.handshake()
                .writeFrame(new Http2HeadersFrame(1, false, RFCTestUtils.postHelloHeaders(server.uri().getPort())))
                .flush();
            assertThat(handlerStarted.await(5, TimeUnit.SECONDS), equalTo(true));

            byte[] opaqueData = new byte[] {1, 2, 3, 4, 5, 6, 7, 8};
            con.writeFrame(new Http2ResetStreamFrame(1, Http2ErrorCode.CANCEL.code()))
                .writeFrame(new Http2Ping(false, opaqueData))
                .flush();
            assertThat(con.readLogicalFrame(Http2Ping.class), equalTo(new Http2Ping(true, opaqueData)));

            con.writeFrame(new Http2HeadersFrame(3, true, getHelloHeaders()))
                .flush();
            var accepted = con.readLogicalFrame(Http2HeadersFrame.class);
            assertThat(accepted.streamId(), equalTo(3));
            assertThat(accepted.headers().get(":status"), equalTo("202"));
        } finally {
            releaseHandler.countDown();
        }
    }

    @Test
    public void cleanEofDoesNotFailAProtocolClosedStreamRetainedForApplicationCleanup() throws Exception {
        var responseEnded = new CountDownLatch(1);
        var releaseHandler = new CountDownLatch(1);
        var exchangeCompleted = new CountDownLatch(1);

        server = httpServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addResponseCompleteListener(info -> exchangeCompleted.countDown())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> {
                try (var output = response.outputStream(1)) {
                    output.write('x');
                }
                responseEnded.countDown();
                releaseHandler.await(5, TimeUnit.SECONDS);
            })
            .start();

        try (var client = new H2Client();
             var con = client.connectClearText(server)) {
            con.handshake()
                .writeFrame(new Http2HeadersFrame(
                    1,
                    true,
                    RFCTestUtils.getHelloHeaders(
                        "http",
                        java.util.Objects.requireNonNull(server.httpUri()).getPort()
                    )
                ))
                .flush();

            assertThat(responseEnded.await(5, TimeUnit.SECONDS), equalTo(true));
            readIgnoringWindowUpdates(con, Http2HeadersFrame.class);
            readIgnoringWindowUpdates(con, Http2DataFrame.class);
            assertThat(readIgnoringWindowUpdates(con, Http2DataFrame.class).endStream(), equalTo(true));

            var connection = (Http2Connection) server.activeConnections().iterator().next();
            Http2StreamRegistry streamRegistry =
                getField(connection, "streamRegistry", Http2StreamRegistry.class);
            var stream = Objects.requireNonNull(streamRegistry.applicationStream(1));
            assertEventually(stream::protocolStateClosed, equalTo(true));

            con.socket().shutdownOutput();
            assertEventually(
                () -> getField(connection, "readState", Object.class).toString(),
                equalTo("COMPLETED")
            );
        } finally {
            releaseHandler.countDown();
        }

        assertThat(exchangeCompleted.await(5, TimeUnit.SECONDS), equalTo(true));
    }

    @Test
    public void clientDisconnectRetiresAnEarlyResponseStreamWithoutEndStream() throws Exception {
        var exchangeCompleted = new CountDownLatch(1);

        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addResponseCompleteListener(info -> exchangeCompleted.countDown())
            .addHandler(Method.POST, "/hello", (request, response, pathParams) -> {
                response.status(204);
            })
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            con.handshake()
                .writeFrame(new Http2HeadersFrame(1, false, RFCTestUtils.postHelloHeaders(server.uri().getPort())))
                .flush();

            var response = con.readLogicalFrame(Http2HeadersFrame.class);
            assertThat(response.headers().get(":status"), equalTo("204"));
            assertThat(exchangeCompleted.await(5, TimeUnit.SECONDS), equalTo(true));
        }

        assertEventually(() -> server.activeConnections(), empty());
    }

    private @NonNull FieldBlock getHelloHeaders() {
        return RFCTestUtils.getHelloHeaders(server.uri().getPort());
    }

    private static <T> T getField(Object target, String name, Class<T> type) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(target));
    }

    @AfterEach
    public void stop() {
        if (server != null) server.stop();
    }

}
