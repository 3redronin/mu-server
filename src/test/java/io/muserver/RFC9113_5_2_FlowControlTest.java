package io.muserver;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.muserver.MuServerBuilder.httpsServer;
import static io.muserver.RFCTestUtils.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static scaffolding.MuAssert.assertNotTimedOut;

@DisplayName("RFC 9113 5.2 Flow Control")
class RFC9113_5_2_FlowControlTest {

    private @Nullable MuServer server;

    @Test
    void peerCanUseConnectionCreditBeforeWindowUpdateFlushReturns() throws Exception {
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
                    ConnectionAcceptedTime.now(),
                    Http2Settings.DEFAULT_CLIENT_SETTINGS,
                    5000,
                    executor,
                    executor
                );
                Http2InboundFlowControl flow = writerConnection.testProbe().inbound();
                flow.openStream(1, 100_000);
                assertThat(flow.reserve(1, 65_535).error(), nullValue());

                writerConnection.returnInboundCredit(1, 32_768, false);

                var debitObservedDuringWrite =
                    new AtomicReference<Http2InboundFlowControl.Result>();
                var flushEntered = new CountDownLatch(1);
                var debitObservedDuringFlush =
                    new AtomicReference<Http2InboundFlowControl.Result>();
                var output = new ByteArrayOutputStream() {
                    @Override
                    public synchronized void write(byte[] data, int offset, int length) {
                        debitObservedDuringWrite.compareAndSet(
                            null,
                            flow.reserve(1, 1)
                        );
                        super.write(data, offset, length);
                    }

                    @Override
                    public void flush() {
                        debitObservedDuringFlush.set(flow.reserve(1, 1));
                        flushEntered.countDown();
                    }
                };

                writerConnection.startWriteLoop(output);
                assertNotTimedOut("waiting for WINDOW_UPDATE flush", flushEntered);
                assertThat(
                    debitObservedDuringWrite.get().error(),
                    nullValue()
                );
                assertThat(
                    debitObservedDuringFlush.get().error(),
                    nullValue()
                );
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void earlyResponseDiscardsTheRemainingRequestWithoutExhaustingConnectionCredit() throws Exception {
        var exchangeCompleted = new CountDownLatch(1);

        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addResponseCompleteListener(info -> exchangeCompleted.countDown())
            .addHandler(Method.POST, "/early", (request, response, pathParams) -> {
                response.status(204);
            })
            .addHandler(Method.POST, "/hello", (request, response, pathParams) -> {
                response.write(request.readBodyAsString());
            })
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            var earlyHeaders = new FieldBlock();
            earlyHeaders.add(":scheme", "https");
            earlyHeaders.add(":authority", "localhost:" + getPort());
            earlyHeaders.add(":method", "POST");
            earlyHeaders.add(":path", "/early");
            earlyHeaders.add("content-type", "text/plain; charset=utf-8");

            byte[] sixteenKb = repeated('a', 16384);
            byte[] lastChunk = repeated('b', 16383);

            con.handshake()
                .writeFrame(new Http2HeadersFrame(1, false, earlyHeaders))
                .flush();

            var earlyResponse = readIgnoringWindowUpdates(con, Http2HeadersFrame.class);
            assertThat(earlyResponse.streamId(), equalTo(1));
            assertThat(earlyResponse.headers().get(":status"), equalTo("204"));
            assertThat(exchangeCompleted.await(5, TimeUnit.SECONDS), equalTo(true));

            con.writeFrame(new Http2DataFrame(1, false, sixteenKb, 0, sixteenKb.length))
                .writeFrame(new Http2DataFrame(1, false, sixteenKb, 0, sixteenKb.length))
                .writeFrame(new Http2DataFrame(1, false, sixteenKb, 0, sixteenKb.length))
                .writeFrame(new Http2DataFrame(1, true, lastChunk, 0, lastChunk.length))
                .flush();

            Http2WindowUpdate reusableConnectionCredit =
                readConnectionWindowUpdateIgnoringStreamOneResets(con);
            assertThat(reusableConnectionCredit.streamId(), equalTo(0));

            con.writeFrame(new Http2HeadersFrame(3, false, postHelloHeaders(getPort())))
                .writeFrame(RFCTestUtils.utf8DataFrame(3, true, "x"))
                .flush();

            var headers = readIgnoringWindowUpdates(con, Http2HeadersFrame.class);
            assertThat(headers.streamId(), equalTo(3));
            assertThat(headers.headers().get(":status"), equalTo("200"));

            var data = readIgnoringWindowUpdates(con, Http2DataFrame.class);
            assertThat(data.streamId(), equalTo(3));
            assertThat(data.toUTF8(), equalTo("x"));
            assertThat(readIgnoringWindowUpdates(con, Http2DataFrame.class), equalTo(Http2DataFrame.eos(3)));
        }
    }

    @Test
    void earlyResponseReturnsStreamCreditWhileDiscardingTheRequestBody() throws Exception {
        var exchangeCompleted = new CountDownLatch(1);
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addResponseCompleteListener(info -> exchangeCompleted.countDown())
            .addHandler(Method.POST, "/early", (request, response, pathParams) -> {
                response.status(204);
            })
            .addHandler(Method.GET, "/after", (request, response, pathParams) -> {
                response.write("ok");
            })
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            var headers = new FieldBlock();
            headers.add(":scheme", "https");
            headers.add(":authority", "localhost:" + getPort());
            headers.add(":method", "POST");
            headers.add(":path", "/early");
            headers.add("content-type", "text/plain; charset=utf-8");
            con.handshake()
                .writeFrame(new Http2HeadersFrame(1, false, headers))
                .flush();
            assertThat(
                con.readLogicalFrame(Http2HeadersFrame.class).headers().get(":status"),
                equalTo("204")
            );
            assertThat(exchangeCompleted.await(5, TimeUnit.SECONDS), equalTo(true));

            byte[] sixteenKb = repeated('a', 16_384);
            con.writeFrame(new Http2DataFrame(1, false, sixteenKb, 0, sixteenKb.length))
                .writeFrame(new Http2DataFrame(1, false, sixteenKb, 0, sixteenKb.length))
                .flush();

            Http2WindowUpdate streamUpdate;
            do {
                streamUpdate = con.readLogicalFrame(Http2WindowUpdate.class);
            } while (streamUpdate.streamId() == 0);
            assertThat(streamUpdate, equalTo(new Http2WindowUpdate(1, 32_768)));

            // The peer can use the returned stream credit to finish a body
            // larger than the initial 65,535-byte stream window.
            var afterRequest = getHelloHeaders("https", getPort());
            afterRequest.set(":path", "/after");
            con.writeFrame(new Http2DataFrame(1, false, sixteenKb, 0, sixteenKb.length))
                .writeFrame(new Http2DataFrame(1, false, sixteenKb, 0, sixteenKb.length))
                .writeFrame(RFCTestUtils.utf8DataFrame(1, true, "done"))
                .writeFrame(new Http2HeadersFrame(3, true, afterRequest))
                .flush();

            var afterHeaders = readIgnoringWindowUpdates(con, Http2HeadersFrame.class);
            assertThat(afterHeaders.streamId(), equalTo(3));
            assertThat(afterHeaders.headers().get(":status"), equalTo("200"));
            assertThat(
                readIgnoringWindowUpdates(con, Http2DataFrame.class).toUTF8(),
                equalTo("ok")
            );
            assertThat(
                readIgnoringWindowUpdates(con, Http2DataFrame.class),
                equalTo(Http2DataFrame.eos(3))
            );
        }
    }

    @Test
    void cancellingAStreamWithUnreadQueuedDataAdvertisesRefundedConnectionCredit() throws Exception {
        var holdLatch = new CountDownLatch(1);
        var holdStarted = new CountDownLatch(1);

        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.POST, "/hold", (request, response, pathParams) -> {
                holdStarted.countDown();
                assertNotTimedOut("waiting for hold request to finish", holdLatch);
                try {
                    request.readBodyAsString();
                } catch (Exception ignored) {
                }
            })
            .addHandler(Method.POST, "/hello", (request, response, pathParams) -> {
                response.write(request.readBodyAsString());
            })
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            byte[] sixteenKb = repeated('a', 16384);
            byte[] lastChunk = repeated('b', 16383);

            con.handshake()
                .writeFrame(new Http2HeadersFrame(
                    1,
                    false,
                    postHeaders(getPort(), "/hold")
                ))
                .writeFrame(new Http2DataFrame(1, false, sixteenKb, 0, sixteenKb.length))
                .writeFrame(new Http2DataFrame(1, false, sixteenKb, 0, sixteenKb.length))
                .writeFrame(new Http2DataFrame(1, false, sixteenKb, 0, sixteenKb.length))
                .writeFrame(new Http2DataFrame(1, false, lastChunk, 0, lastChunk.length))
                .flush();

            assertNotTimedOut("waiting for held request to start", holdStarted);
            con.writeFrame(new Http2ResetStreamFrame(1, Http2ErrorCode.CANCEL.code()))
                .flush();

            assertThat(
                con.readLogicalFrame(),
                equalTo(new Http2WindowUpdate(0, 65535))
            );

            con.writeFrame(new Http2HeadersFrame(
                    3,
                    false,
                    postHelloHeaders(getPort())
                ))
                .writeFrame(RFCTestUtils.utf8DataFrame(3, true, "x"))
                .flush();

            var headers = readIgnoringWindowUpdatesAndStreamOneResets(con, Http2HeadersFrame.class);
            assertThat(headers.streamId(), equalTo(3));
            assertThat(headers.headers().get(":status"), equalTo("200"));

            var data = readIgnoringWindowUpdatesAndStreamOneResets(con, Http2DataFrame.class);
            assertThat(data.streamId(), equalTo(3));
            assertThat(data.toUTF8(), equalTo("x"));

            var eos = readIgnoringWindowUpdatesAndStreamOneResets(con, Http2DataFrame.class);
            assertThat(eos, equalTo(Http2DataFrame.eos(3)));
        } finally {
            holdLatch.countDown();
        }
    }

    @Test
    void locallyResettingAStreamRefundsUnreadConnectionCredit() throws Exception {
        var failRequest = new CountDownLatch(1);

        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.POST, "/fail", (request, response, pathParams) -> {
                response.sendChunk("partial");
                assertNotTimedOut("waiting to fail request", failRequest);
                throw new IllegalStateException("expected test failure");
            })
            .addHandler(Method.POST, "/hello", (request, response, pathParams) -> {
                response.write(request.readBodyAsString());
            })
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {
            con.handshake()
                .writeFrame(new Http2HeadersFrame(
                    1,
                    false,
                    postHeaders(getPort(), "/fail")
                ))
                .flush();

            assertThat(
                readIgnoringWindowUpdates(con, Http2HeadersFrame.class)
                    .headers().get(":status"),
                equalTo("200")
            );
            assertThat(
                readIgnoringWindowUpdates(con, Http2DataFrame.class).toUTF8(),
                equalTo("partial")
            );

            byte[] sixteenKb = repeated('a', 16_384);
            byte[] lastChunk = repeated('b', 16_383);
            byte[] pingData = new byte[] {1, 2, 3, 4, 5, 6, 7, 8};
            con.writeFrame(new Http2DataFrame(1, false, sixteenKb, 0, sixteenKb.length))
                .writeFrame(new Http2DataFrame(1, false, sixteenKb, 0, sixteenKb.length))
                .writeFrame(new Http2DataFrame(1, false, sixteenKb, 0, sixteenKb.length))
                .writeFrame(new Http2DataFrame(1, false, lastChunk, 0, lastChunk.length))
                .writeFrame(new Http2Ping(false, pingData))
                .flush();

            assertThat(
                con.readLogicalFrame(Http2Ping.class),
                equalTo(new Http2Ping(true, pingData))
            );
            failRequest.countDown();

            var reset = con.readLogicalFrame(Http2ResetStreamFrame.class);
            assertThat(reset.streamId(), equalTo(1));
            assertThat(reset.errorCodeEnum(), equalTo(Http2ErrorCode.INTERNAL_ERROR));
            assertThat(
                readConnectionWindowUpdateIgnoringStreamOneResets(con),
                equalTo(new Http2WindowUpdate(0, 65_535))
            );

            con.writeFrame(new Http2HeadersFrame(
                    3,
                    false,
                    postHelloHeaders(getPort())
                ))
                .writeFrame(utf8DataFrame(3, true, "x"))
                .flush();

            var headers =
                readIgnoringWindowUpdates(con, Http2HeadersFrame.class);
            assertThat(headers.streamId(), equalTo(3));
            assertThat(headers.headers().get(":status"), equalTo("200"));
            assertThat(
                readIgnoringWindowUpdates(con, Http2DataFrame.class).toUTF8(),
                equalTo("x")
            );
            assertThat(
                readIgnoringWindowUpdates(con, Http2DataFrame.class),
                equalTo(Http2DataFrame.eos(3))
            );
        } finally {
            failRequest.countDown();
        }
    }

    @Test
    void dataFramesRejectedAtStreamLevelStillRefundConnectionCredit() throws Exception {
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.POST, "/hello", (request, response, pathParams) -> {
                response.write(request.readBodyAsString());
            })
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            byte[] sixteenKb = repeated('a', 16384);
            // The first request body used two bytes that were consumed below the
            // update threshold, so the peer still has 65,533 advertised bytes.
            byte[] lastChunk = repeated('b', 16381);

            con.handshake()
                .writeFrame(new Http2HeadersFrame(1, false, postHelloHeaders(getPort())))
                .writeFrame(RFCTestUtils.utf8DataFrame(1, true, "ok"))
                .flush();

            var firstHeaders = readIgnoringWindowUpdatesAndStreamOneResets(con, Http2HeadersFrame.class);
            assertThat(firstHeaders.streamId(), equalTo(1));
            assertThat(firstHeaders.headers().get(":status"), equalTo("200"));
            assertThat(readIgnoringWindowUpdatesAndStreamOneResets(con, Http2DataFrame.class).toUTF8(), equalTo("ok"));
            assertThat(readIgnoringWindowUpdatesAndStreamOneResets(con, Http2DataFrame.class), equalTo(Http2DataFrame.eos(1)));

            con.writeFrame(new Http2DataFrame(1, false, sixteenKb, 0, sixteenKb.length))
                .writeFrame(new Http2DataFrame(1, false, sixteenKb, 0, sixteenKb.length))
                .writeFrame(new Http2DataFrame(1, false, sixteenKb, 0, sixteenKb.length))
                .writeFrame(new Http2DataFrame(1, false, lastChunk, 0, lastChunk.length))
                .flush();

            assertThat(
                readConnectionWindowUpdateIgnoringStreamOneResets(con).streamId(),
                equalTo(0)
            );

            con.writeFrame(new Http2HeadersFrame(3, false, postHelloHeaders(getPort())))
                .writeFrame(RFCTestUtils.utf8DataFrame(3, true, "x"))
                .flush();

            var secondHeaders = readIgnoringWindowUpdatesAndStreamOneResets(con, Http2HeadersFrame.class);
            assertThat(secondHeaders.streamId(), equalTo(3));
            assertThat(secondHeaders.headers().get(":status"), equalTo("200"));

            var secondData = readIgnoringWindowUpdatesAndStreamOneResets(con, Http2DataFrame.class);
            assertThat(secondData.streamId(), equalTo(3));
            assertThat(secondData.toUTF8(), equalTo("x"));
            assertThat(readIgnoringWindowUpdatesAndStreamOneResets(con, Http2DataFrame.class), equalTo(Http2DataFrame.eos(3)));
        }
    }

    @Test
    void blockedDataDoesNotBlockPingAck() throws Exception {
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> {
                response.write("hello");
            })
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            con.handshake()
                .writeRaw(settingsFrame(4, 0))
                .flush();

            assertThat(con.readLogicalFrame(), equalTo(Http2Settings.ACK));

            con.writeFrame(new Http2HeadersFrame(1, true, getHelloHeaders(getPort())))
                .flush();

            var headers = readIgnoringWindowUpdatesAndStreamOneResets(con, Http2HeadersFrame.class);
            assertThat(headers.streamId(), equalTo(1));
            assertThat(headers.headers().get(":status"), equalTo("200"));

            assertNothingToRead(con.socket());

            byte[] opaqueData = new byte[] {1, 2, 3, 4, 5, 6, 7, 8};
            con.writeFrame(new Http2Ping(false, opaqueData))
                .flush();

            assertThat(con.readLogicalFrame(Http2Ping.class), equalTo(new Http2Ping(true, opaqueData)));

            assertNothingToRead(con.socket());

            con.writeFrame(new Http2WindowUpdate(1, 5))
                .flush();

            var data = readIgnoringWindowUpdatesAndStreamOneResets(con, Http2DataFrame.class);
            assertThat(data.streamId(), equalTo(1));
            assertThat(data.toUTF8(), equalTo("hello"));
            assertThat(readIgnoringWindowUpdatesAndStreamOneResets(con, Http2DataFrame.class), equalTo(Http2DataFrame.eos(1)));
        }
    }

    @Test
    void streamCreditOfOneShouldEmitHelloOneByteAtATime() throws Exception {
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> {
                response.write("hello");
            })
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            con.handshake()
                .writeRaw(settingsFrame(4, 1))
                .flush();

            assertThat(con.readLogicalFrame(), equalTo(Http2Settings.ACK));

            con.writeFrame(new Http2HeadersFrame(1, true, getHelloHeaders(getPort())))
                .flush();

            var headers = readIgnoringWindowUpdates(con, Http2HeadersFrame.class);
            assertThat(headers.streamId(), equalTo(1));
            assertThat(headers.headers().get(":status"), equalTo("200"));

            con.socket().setSoTimeout(2000);
            assertHelloArrivesOneByteAtATime(con, 1, 1);
        }
    }

    @Test
    void connectionCreditOfOneShouldEmitHelloOneByteAtATime() throws Exception {
        byte[] body = repeated('x', 65534);

        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/drain", (request, response, pathParams) -> {
                response.outputStream().write(body);
            })
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> {
                response.write("hello");
            })
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            var drainHeaders = getHelloHeaders(getPort());
            drainHeaders.set(":path", "/drain");

            con.handshake()
                .writeFrame(new Http2HeadersFrame(1, true, drainHeaders))
                .flush();

            var drainResponse = readIgnoringWindowUpdates(con, Http2HeadersFrame.class);
            assertThat(drainResponse.streamId(), equalTo(1));
            assertThat(drainResponse.headers().get(":status"), equalTo("200"));

            int received = 0;
            while (received < body.length) {
                var frame = readIgnoringWindowUpdates(con, Http2DataFrame.class);
                assertThat(frame.streamId(), equalTo(1));
                received += frame.payloadLength();
            }
            assertThat(received, equalTo(body.length));
            assertThat(readIgnoringWindowUpdates(con, Http2DataFrame.class), equalTo(Http2DataFrame.eos(1)));

            con.writeFrame(new Http2HeadersFrame(3, true, getHelloHeaders(getPort())))
                .flush();

            var headers = readIgnoringWindowUpdates(con, Http2HeadersFrame.class);
            assertThat(headers.streamId(), equalTo(3));
            assertThat(headers.headers().get(":status"), equalTo("200"));

            con.socket().setSoTimeout(2000);
            assertHelloArrivesOneByteAtATime(con, 3, 0);
        }
    }

    private static byte[] settingsFrame(int identifier, long value) {
        return new byte[] {
            0, 0, 6,
            0x04,
            0,
            0, 0, 0, 0,
            (byte) (identifier >> 8),
            (byte) identifier,
            (byte) (value >> 24),
            (byte) (value >> 16),
            (byte) (value >> 8),
            (byte) value
        };
    }

    private static <T extends LogicalHttp2Frame> T readIgnoringWindowUpdatesAndStreamOneResets(H2ClientConnection con, Class<T> clazz) throws Exception {
        while (true) {
            var frame = con.readLogicalFrame();
            if (clazz.isAssignableFrom(frame.getClass())) {
                return clazz.cast(frame);
            }
            if (frame instanceof Http2WindowUpdate) {
                continue;
            }
            if (frame instanceof Http2ResetStreamFrame) {
                var reset = (Http2ResetStreamFrame) frame;
                if (reset.streamId() == 1) {
                    continue;
                }
            }
            throw new IllegalStateException("Expected " + clazz.getName() + ", got " + frame);
        }
    }

    private static Http2WindowUpdate readConnectionWindowUpdateIgnoringStreamOneResets(
        H2ClientConnection con
    ) throws Exception {
        while (true) {
            LogicalHttp2Frame frame = con.readLogicalFrame();
            if (frame instanceof Http2WindowUpdate) {
                var update = (Http2WindowUpdate) frame;
                if (update.streamId() == 0) {
                    return update;
                }
                continue;
            }
            if (frame instanceof Http2ResetStreamFrame
                && frame.streamId() == 1) {
                continue;
            }
            throw new IllegalStateException(
                "Expected a connection WINDOW_UPDATE, got " + frame
            );
        }
    }

    private static void assertHelloArrivesOneByteAtATime(H2ClientConnection con, int streamId, int windowUpdateStreamId) throws Exception {
        for (char expected : "hello".toCharArray()) {
            var data = readIgnoringWindowUpdates(con, Http2DataFrame.class);
            assertThat(data.streamId(), equalTo(streamId));
            assertThat(data.toUTF8(), equalTo(String.valueOf(expected)));
            assertThat(data.endStream(), equalTo(false));
            if (expected != 'o') {
                con.writeFrame(new Http2WindowUpdate(windowUpdateStreamId, 1))
                    .flush();
            }
        }
        assertThat(readIgnoringWindowUpdates(con, Http2DataFrame.class), equalTo(Http2DataFrame.eos(streamId)));
    }

    private byte[] repeated(char c, int count) {
        byte[] bytes = new byte[count];
        java.util.Arrays.fill(bytes, (byte) c);
        return bytes;
    }

    private int getPort() {
        return server.uri().getPort();
    }

    @SuppressWarnings("unchecked")


    @AfterEach
    public void stop() {
        if (server != null) server.stop();
    }
}
