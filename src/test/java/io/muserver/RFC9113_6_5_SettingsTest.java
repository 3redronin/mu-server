package io.muserver;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static io.muserver.MuServerBuilder.httpsServer;
import static io.muserver.RFCTestUtils.assertNothingToRead;
import static io.muserver.RFCTestUtils.goAway;
import static io.muserver.RFCTestUtils.getHelloHeaders;
import static io.muserver.RFCTestUtils.readIgnoringWindowUpdates;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static scaffolding.MuAssert.assertNotTimedOut;

@DisplayName("RFC 9113 6.5 Frame Definitions: SETTINGS")
class RFC9113_6_5_SettingsTest {

    private @Nullable MuServer server;

    @Test
    void settingsFramesMustBeOnStream0() throws Exception {
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            con.handshake()
                .writeRaw(new byte[] {
                    0, 0, 0,
                    0x04,
                    0,
                    0, 0, 0, 1
                })
                .flush();

            assertThat(con.readLogicalFrame(), equalTo(goAway(0, Http2ErrorCode.PROTOCOL_ERROR)));
            assertThrows(IOException.class, con::readFrameHeader);
        }
    }

    @Test
    void settingsAckFramesMustHaveEmptyPayload() throws Exception {
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            con.handshake()
                .writeRaw(new byte[] {
                    0, 0, 6,
                    0x04,
                    0x01,
                    0, 0, 0, 0,
                    0, 1, 0, 0, 0, 1
                })
                .flush();

            assertThat(con.readLogicalFrame(), equalTo(goAway(0, Http2ErrorCode.FRAME_SIZE_ERROR)));
            assertThrows(IOException.class, con::readFrameHeader);
        }
    }

    @Test
    void settingsFramesPayloadLengthMustBeAMultipleOf6() throws Exception {
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            con.handshake()
                .writeRaw(new byte[] {
                    0, 0, 3,
                    0x04,
                    0,
                    0, 0, 0, 0,
                    0, 1, 0
                })
                .flush();

            assertThat(con.readLogicalFrame(), equalTo(goAway(0, Http2ErrorCode.FRAME_SIZE_ERROR)));
            assertThrows(IOException.class, con::readFrameHeader);
        }
    }

    @Test
    void settingsAckWithoutPendingSettingsIsAConnectionError() throws Exception {
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            con.handshake()
                .writeFrame(Http2Settings.ACK)
                .flush();

            assertThat(con.readLogicalFrame(), equalTo(goAway(0, Http2ErrorCode.PROTOCOL_ERROR)));
            assertThrows(IOException.class, con::readFrameHeader);
        }
    }

    @Test
    void missingAckForTheServersSettingsLeadsToASettingsTimeout() throws Exception {
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled().withSettingsAckTimeoutMillis(200))
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            con.writePreface();
            con.writeFrame(Http2Settings.DEFAULT_CLIENT_SETTINGS);
            con.flush();

            assertThat(con.readLogicalFrame(Http2Settings.class), equalTo(new Http2Settings(false, 4096, 200, 65535, 16384, 8192)));
            assertThat(con.readLogicalFrame(), equalTo(Http2Settings.ACK));

            assertThat(con.readLogicalFrame(), equalTo(goAway(0, Http2ErrorCode.SETTINGS_TIMEOUT)));
            assertThrows(IOException.class, con::readFrameHeader);
        }
    }

    @Test
    void invalidInitialWindowSizeIsAFlowControlError() throws Exception {
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            con.handshake()
                .writeRaw(settingsFrame(4, 0xFFFFFFFFL))
                .flush();

            assertThat(con.readLogicalFrame(), equalTo(goAway(0, Http2ErrorCode.FLOW_CONTROL_ERROR)));
            assertThrows(IOException.class, con::readFrameHeader);
        }
    }

    @Test
    void invalidMaxFrameSizeIsAProtocolError() throws Exception {
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            con.handshake()
                .writeRaw(settingsFrame(5, 16383))
                .flush();

            assertThat(con.readLogicalFrame(), equalTo(goAway(0, Http2ErrorCode.PROTOCOL_ERROR)));
            assertThrows(IOException.class, con::readFrameHeader);
        }
    }

    @Test
    void ordinarySettingsFramesAreAcked() throws Exception {
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            con.handshake()
                .writeRaw(settingsFrame(1, 8192))
                .flush();

            assertThat(con.readLogicalFrame(), equalTo(Http2Settings.ACK));
        }
    }

    @Test
    void maxFrameSizeChangesAffectSubsequentResponses() throws Exception {
        byte[] body = new byte[20001];
        Arrays.fill(body, (byte) 'x');

        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> {
                response.outputStream().write(body);
            })
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            con.handshake()
                .writeRaw(settingsFrame(5, 20000))
                .flush();

            assertThat(con.readLogicalFrame(), equalTo(Http2Settings.ACK));

            con.writeFrame(new Http2HeadersFrame(1, true, getHelloHeaders(getPort())))
                .flush();

            var headers = con.readLogicalFrame(Http2HeadersFrame.class);
            assertThat(headers.headers().get(":status"), equalTo("200"));

            var first = con.readLogicalFrame(Http2DataFrame.class);
            var second = con.readLogicalFrame(Http2DataFrame.class);
            var eos = con.readLogicalFrame(Http2DataFrame.class);

            assertThat(Arrays.asList(first.payloadLength(), second.payloadLength(), eos.payloadLength()), contains(20000, 1, 0));
            assertThat(first.endStream(), equalTo(false));
            assertThat(second.endStream(), equalTo(false));
            assertThat(eos.endStream(), equalTo(true));
            assertThat(
                new String(first.payload(), first.payloadOffset(), first.payloadLength(), StandardCharsets.UTF_8)
                    + new String(second.payload(), second.payloadOffset(), second.payloadLength(), StandardCharsets.UTF_8),
                equalTo(new String(body, StandardCharsets.UTF_8))
            );
        }
    }

    @Test
    void initialWindowSizeChangesShouldAffectExistingStreams() throws Exception {
        var goTime = new CountDownLatch(1);
        var requestStarted = new CountDownLatch(1);

        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> {
                requestStarted.countDown();
                assertNotTimedOut("waiting to write response", goTime);
                response.write("hello");
            })
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            con.handshake()
                .writeFrame(new Http2HeadersFrame(1, true, getHelloHeaders(getPort())))
                .flush();

            assertNotTimedOut("waiting for request to start", requestStarted);

            con.writeRaw(settingsFrame(4, 0))
                .flush();

            assertThat(con.readLogicalFrame(), equalTo(Http2Settings.ACK));

            goTime.countDown();

            var headers = con.readLogicalFrame(Http2HeadersFrame.class);
            assertThat(headers.headers().get(":status"), equalTo("200"));

            // If the setting is applied to the existing stream, then the server should not be able to send
            // response body bytes until the client grants more credit.
            assertNothingToRead(con.socket());

            con.writeFrame(new Http2WindowUpdate(1, 5))
                .flush();

            assertThat(con.readLogicalFrame(Http2DataFrame.class).toUTF8(), equalTo("hello"));
            assertThat(con.readLogicalFrame(), equalTo(Http2DataFrame.eos(1)));
        }
    }

    @Test
    void increasingInitialWindowSizeCanUnblockAnExistingStreamWithoutAStreamWindowUpdate() throws Exception {
        var goTime = new CountDownLatch(1);
        var requestStarted = new CountDownLatch(1);

        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> {
                requestStarted.countDown();
                assertNotTimedOut("waiting to write response", goTime);
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

            assertNotTimedOut("waiting for request to start", requestStarted);

            goTime.countDown();

            var headers = con.readLogicalFrame(Http2HeadersFrame.class);
            assertThat(headers.headers().get(":status"), equalTo("200"));

            assertNothingToRead(con.socket());

            con.writeRaw(settingsFrame(4, 5))
                .flush();

            assertThat(con.readLogicalFrame(), equalTo(Http2Settings.ACK));
            assertThat(con.readLogicalFrame(Http2DataFrame.class).toUTF8(), equalTo("hello"));
            assertThat(con.readLogicalFrame(), equalTo(Http2DataFrame.eos(1)));
        }
    }

    @Test
    void shrinkingInitialWindowSizeCanMakeOneExistingStreamNegativeWithoutBlockingAnother() throws Exception {
        var stream1MaySendSecondChunk = new CountDownLatch(1);
        var stream3MayWrite = new CountDownLatch(1);
        var stream1Started = new CountDownLatch(1);
        var stream3Started = new CountDownLatch(1);

        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/s1", (request, response, pathParams) -> {
                stream1Started.countDown();
                response.sendChunk("0123456789");
                assertNotTimedOut("waiting for second s1 chunk", stream1MaySendSecondChunk);
                response.sendChunk("A");
            })
            .addHandler(Method.GET, "/s3", (request, response, pathParams) -> {
                stream3Started.countDown();
                assertNotTimedOut("waiting for s3 to write", stream3MayWrite);
                response.write("B");
            })
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            var s1Headers = getHelloHeaders(getPort());
            s1Headers.set(":path", "/s1");
            var s3Headers = getHelloHeaders(getPort());
            s3Headers.set(":path", "/s3");

            con.handshake()
                .writeFrame(new Http2HeadersFrame(1, true, s1Headers))
                .writeFrame(new Http2HeadersFrame(3, true, s3Headers))
                .flush();

            assertNotTimedOut("waiting for s1 to start", stream1Started);
            assertNotTimedOut("waiting for s3 to start", stream3Started);

            var headers1 = readIgnoringWindowUpdates(con, Http2HeadersFrame.class);
            assertThat(headers1.streamId(), equalTo(1));
            assertThat(headers1.headers().get(":status"), equalTo("200"));

            var firstChunk = readIgnoringWindowUpdates(con, Http2DataFrame.class);
            assertThat(firstChunk.streamId(), equalTo(1));
            assertThat(firstChunk.toUTF8(), equalTo("0123456789"));
            assertThat(firstChunk.endStream(), equalTo(false));

            con.writeRaw(settingsFrame(4, 5))
                .flush();

            assertThat(con.readLogicalFrame(), equalTo(Http2Settings.ACK));

            stream1MaySendSecondChunk.countDown();
            stream3MayWrite.countDown();

            var headers3 = readIgnoringWindowUpdatesForStream(con, Http2HeadersFrame.class, 3);
            assertThat(headers3.headers().get(":status"), equalTo("200"));

            var stream3Data = readIgnoringWindowUpdatesForStream(con, Http2DataFrame.class, 3);
            assertThat(stream3Data.toUTF8(), equalTo("B"));
            assertThat(stream3Data.endStream(), equalTo(false));
            assertThat(readIgnoringWindowUpdatesForStream(con, Http2DataFrame.class, 3), equalTo(Http2DataFrame.eos(3)));

            assertNothingToRead(con.socket());

            con.writeFrame(new Http2WindowUpdate(1, 5))
                .flush();

            assertNothingToRead(con.socket());

            con.writeFrame(new Http2WindowUpdate(1, 1))
                .flush();

            var secondChunk = readIgnoringWindowUpdatesForStream(con, Http2DataFrame.class, 1);
            assertThat(secondChunk.toUTF8(), equalTo("A"));
            assertThat(secondChunk.endStream(), equalTo(false));
            assertThat(readIgnoringWindowUpdatesForStream(con, Http2DataFrame.class, 1), equalTo(Http2DataFrame.eos(1)));
        }
    }

    @Test
    void serverSettingsAreOnlyAckPendingAfterTheyAreSent() throws Exception {
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled().withSettingsAckTimeoutMillis(5000))
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            con.handshake();

            var liveConnection = (Http2Connection) server.activeConnections().iterator().next();
            var executor = Executors.newSingleThreadExecutor();
            try {
                var queuedOnlyConnection = new Http2Connection(
                    liveConnection.server,
                    liveConnection.creator,
                    liveConnection.clientSocket,
                    liveConnection.clientCertificate,
                    Instant.now(),
                    Http2Settings.DEFAULT_CLIENT_SETTINGS,
                    5000,
                    executor
                );

                Queue<Long> settingsAckQueue = getField(queuedOnlyConnection, "settingsAckQueue", Queue.class);
                assertThat(settingsAckQueue.size(), equalTo(0));

                queuedOnlyConnection.write(new Http2Settings(false, 8192, 123, 65535, 16384, 32768));

                assertThat(settingsAckQueue.size(), equalTo(0));
            } finally {
                executor.shutdownNow();
            }
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

    private int getPort() {
        return server.uri().getPort();
    }

    private static <T extends LogicalHttp2Frame> T readIgnoringWindowUpdatesForStream(H2ClientConnection con, Class<T> clazz, int streamId) throws IOException, Http2Exception {
        while (true) {
            var frame = con.readLogicalFrame();
            if (frame instanceof Http2WindowUpdate) {
                continue;
            }
            if (clazz.isAssignableFrom(frame.getClass())) {
                if (frame instanceof Http2HeadersFrame) {
                    var headers = (Http2HeadersFrame) frame;
                    if (headers.streamId() == streamId) {
                        return clazz.cast(headers);
                    }
                } else if (frame instanceof Http2DataFrame) {
                    var data = (Http2DataFrame) frame;
                    if (data.streamId() == streamId) {
                        return clazz.cast(data);
                    }
                } else {
                    return clazz.cast(frame);
                }
            }
            throw new IllegalStateException("Expected " + clazz.getName() + " for stream " + streamId + ", got " + frame);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T getField(Object target, String name, Class<T> type) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) type.cast(field.get(target));
    }

    @AfterEach
    public void stop() {
        if (server != null) server.stop();
    }

}
