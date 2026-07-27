package io.muserver;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.muserver.MuServerBuilder.httpsServer;
import static io.muserver.RFCTestUtils.*;
import static io.muserver.FieldBlockEncoderTest.hexToByteArray;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static scaffolding.MuAssert.assertEventually;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("RFC 9113 6.2 Frame Definitions: HEADERS")
class RFC9113_6_2_HeadersTest {

    private @Nullable MuServer server;

    @Test
    void paddedHeadersFramesCanStartARequest() throws Exception {
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> response.status(202))
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            con.handshake()
                .writeRaw(paddedHeadersFrame(1, true, true, encodeFieldBlock(getHelloHeaders(getPort())), 2))
                .flush();

            var response = con.readLogicalFrame(Http2HeadersFrame.class);
            assertThat(response.streamId(), equalTo(1));
            assertThat(response.headers().get(":status"), equalTo("202"));
        }
    }

    @Test
    void headersFramesMustNotBeOnStreamZero() throws Exception {
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            con.handshake()
                .writeFrame(new Http2HeadersFrame(0, true, getHelloHeaders(getPort())))
                .flush();

            assertThat(con.readLogicalFrame(), equalTo(goAway(0, Http2ErrorCode.PROTOCOL_ERROR)));
            assertThrows(java.io.IOException.class, con::readFrameHeader);
        }
    }

    @Test
    void invalidHeaderBlockFragmentsCauseConnectionCompressionErrors() throws Exception {
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            con.handshake()
                .writeRaw(headersFrame(1, true, true, new byte[] {(byte) 0x80}))
                .flush();

            var goaway = con.readLogicalFrame(Http2GoAway.class);
            assertThat(goaway.errorCodeEnum(), equalTo(Http2ErrorCode.COMPRESSION_ERROR));
            assertThrows(java.io.IOException.class, con::readFrameHeader);
        }
    }

    @Test
    void priorityFlagOnHeadersFramesIsIgnored() throws Exception {
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> response.status(202))
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            con.handshake()
                .writeRaw(priorityHeadersFrame(1, true, true, encodeFieldBlock(getHelloHeaders(getPort())), true, 0, 10))
                .flush();

            var response = con.readLogicalFrame(Http2HeadersFrame.class);
            assertThat(response.streamId(), equalTo(1));
            assertThat(response.headers().get(":status"), equalTo("202"));
        }
    }

    @Test
    void headersFramesMustNotDependOnThemselves() throws Exception {
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> response.status(202))
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            var rejectedFieldBlock = new ByteArrayOutputStream();
            rejectedFieldBlock.write(encodeFieldBlock(getHelloHeaders(getPort())));
            // Literal custom-key: custom-header with incremental indexing (RFC 7541 6.2.1).
            rejectedFieldBlock.write(hexToByteArray("400a637573746f6d2d6b65790d637573746f6d2d686561646572"));

            con.handshake()
                .writeRaw(priorityHeadersFrame(1, true, true, rejectedFieldBlock.toByteArray(), false, 1, 10))
                .flush();

            var reset = con.readLogicalFrame(Http2ResetStreamFrame.class);
            assertThat(reset.streamId(), equalTo(1));
            assertThat(reset.errorCodeEnum(), equalTo(Http2ErrorCode.PROTOCOL_ERROR));

            // The rejected field block was still decoded, so the shared HPACK context and
            // reader position remain usable for the next stream.
            var nextFieldBlock = new ByteArrayOutputStream();
            nextFieldBlock.write(encodeFieldBlock(getHelloHeaders(getPort())));
            nextFieldBlock.write(0xbe); // Indexed dynamic-table entry 62 from the rejected block.
            con.writeRaw(headersFrame(3, true, true, nextFieldBlock.toByteArray())).flush();
            var response = con.readLogicalFrame(Http2HeadersFrame.class);
            assertThat(response.streamId(), equalTo(3));
            assertThat(response.headers().get(":status"), equalTo("202"));
        }
    }


    @Test
    void paddedHeadersFramesMustIncludeAPadLengthByte() throws Exception {
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            con.handshake()
                .writeRaw(headersFrameWithMissingPadLength(1))
                .flush();

            assertThat(con.readLogicalFrame(), equalTo(goAway(0, Http2ErrorCode.FRAME_SIZE_ERROR)));
            assertThrows(java.io.IOException.class, con::readFrameHeader);
        }
    }

    @Test
    void paddedHeadersFramesCannotHavePaddingLongerThanThePayload() throws Exception {
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            con.handshake()
                .writeRaw(headersFrameWithPaddingLongerThanPayload(1))
                .flush();

            assertThat(con.readLogicalFrame(), equalTo(goAway(0, Http2ErrorCode.PROTOCOL_ERROR)));
            assertThrows(java.io.IOException.class, con::readFrameHeader);
        }
    }

    @Test
    void unknownRequestPseudoHeadersAreStreamErrors() throws Exception {
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> response.status(202))
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            FieldBlock invalid = FieldBlock.newWithDate();
            invalid.add(":scheme", "https");
            invalid.add(":authority", "localhost:" + getPort());
            invalid.add(":method", "GET");
            invalid.add(":path", "/hello");
            invalid.add(":status", "200");

            con.handshake()
                .writeFrame(new Http2HeadersFrame(1, true, invalid))
                .flush();

            var reset = con.readLogicalFrame(Http2ResetStreamFrame.class);
            assertThat(reset.streamId(), equalTo(1));
            assertThat(reset.errorCodeEnum(), equalTo(Http2ErrorCode.PROTOCOL_ERROR));

            con.writeFrame(new Http2HeadersFrame(3, true, getHelloHeaders(getPort()))).flush();
            var response = con.readLogicalFrame(Http2HeadersFrame.class);
            assertThat(response.streamId(), equalTo(3));
            assertThat(response.headers().get(":status"), equalTo("202"));
        }
    }

    @Test
    void requestPseudoHeadersMustComeBeforeRegularHeaders() throws Exception {
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> response.status(202))
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            FieldBlock invalid = new FieldBlock();
            invalid.add(":scheme", "https");
            invalid.add(":authority", "localhost:" + getPort());
            invalid.add(":method", "GET");
            invalid.add("accept", "*/*");
            invalid.add(":path", "/hello");

            con.handshake()
                .writeFrame(new Http2HeadersFrame(1, true, invalid))
                .flush();

            var reset = con.readLogicalFrame(Http2ResetStreamFrame.class);
            assertThat(reset.streamId(), equalTo(1));
            assertThat(reset.errorCodeEnum(), equalTo(Http2ErrorCode.PROTOCOL_ERROR));

            con.writeFrame(new Http2HeadersFrame(3, true, getHelloHeaders(getPort()))).flush();
            var response = con.readLogicalFrame(Http2HeadersFrame.class);
            assertThat(response.streamId(), equalTo(3));
            assertThat(response.headers().get(":status"), equalTo("202"));
        }
    }

    @Test
    void trailingHeadersCanBeReadAfterTheRequestBody() throws Exception {
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.POST, "/hello", (request, response, pathParams) -> {
                response.write(request.readBodyAsString() + "|" + request.trailers().get("checksum"));
            })
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            FieldBlock trailers = new FieldBlock();
            trailers.add("checksum", "abc123");

            con.handshake()
                .writeFrame(new Http2HeadersFrame(1, false, postHelloHeaders(getPort())))
                .writeFrame(utf8DataFrame(1, false, "Hello"))
                .writeFrame(new Http2HeadersFrame(1, true, trailers))
                .flush();

            var headers = readIgnoringWindowUpdates(con, Http2HeadersFrame.class);
            assertThat(headers.streamId(), equalTo(1));
            assertThat(headers.headers().get(":status"), equalTo("200"));

            assertThat(readIgnoringWindowUpdates(con, Http2DataFrame.class).toUTF8(), equalTo("Hello|abc123"));
            assertThat(readIgnoringWindowUpdates(con, Http2DataFrame.class).endStream(), equalTo(true));
        }
    }

    @Test
    void dataFollowingTrailersInTheSameWriteIsAStreamClosedError() throws Exception {
        var requestStarted = new CountDownLatch(1);
        var releaseHandler = new CountDownLatch(1);
        byte[] opaqueData = {1, 2, 3, 4, 5, 6, 7, 8};
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.POST, "/hello", (request, response, pathParams) -> {
                requestStarted.countDown();
                releaseHandler.await(10, TimeUnit.SECONDS);
            })
            .start();

        FieldBlock trailers = new FieldBlock();
        trailers.add("checksum", "abc123");

        try (var client = new H2Client();
             var con = client.connect(server)) {
            con.handshake()
                .writeFrame(new Http2HeadersFrame(1, false, postHelloHeaders(getPort())))
                .flush();
            assertThat(requestStarted.await(5, TimeUnit.SECONDS), equalTo(true));

            con.writeFrame(new Http2HeadersFrame(1, true, trailers))
                .writeFrame(utf8DataFrame(1, true, "late"))
                .writeFrame(new Http2Ping(false, opaqueData))
                .flush();

            var reset = readIgnoringWindowUpdates(con, Http2ResetStreamFrame.class);
            assertThat(reset.streamId(), equalTo(1));
            assertThat(reset.errorCodeEnum(), equalTo(Http2ErrorCode.STREAM_CLOSED));
            assertThat(con.readLogicalFrame(Http2Ping.class), equalTo(new Http2Ping(true, opaqueData)));
        } finally {
            releaseHandler.countDown();
        }
    }

    @Test
    void invalidTrailerFieldsAreStreamErrors() throws Exception {
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.POST, "/hello", (request, response, pathParams) -> response.write(request.readBodyAsString()))
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            FieldBlock trailers = new FieldBlock();
            trailers.add("content-length", "123");

            con.handshake()
                .writeFrame(new Http2HeadersFrame(1, false, postHelloHeaders(getPort())))
                .writeFrame(utf8DataFrame(1, false, "Hello"))
                .writeFrame(new Http2HeadersFrame(1, true, trailers))
                .flush();

            var reset = con.readLogicalFrame(Http2ResetStreamFrame.class);
            assertThat(reset.streamId(), equalTo(1));
            assertThat(reset.errorCodeEnum(), equalTo(Http2ErrorCode.PROTOCOL_ERROR));

            con.writeFrame(new Http2HeadersFrame(3, true, postHelloHeaders(getPort()))).flush();
            var response = con.readLogicalFrame(Http2HeadersFrame.class);
            assertThat(response.streamId(), equalTo(3));
            assertThat(response.headers().get(":status"), equalTo("200"));
        }
    }

    @Test
    void oversizedTrailersResetAndRetireTheExistingStream() throws Exception {
        var handlerStarted = new CountDownLatch(1);
        var releaseHandler = new CountDownLatch(1);
        var exchangeCompleted = new CountDownLatch(1);
        server = httpsServer()
            .withMaxHeadersSize(512)
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addResponseCompleteListener(info -> exchangeCompleted.countDown())
            .addHandler(Method.POST, "/hello", (request, response, pathParams) -> {
                handlerStarted.countDown();
                releaseHandler.await(5, TimeUnit.SECONDS);
                response.status(202);
            })
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {
            con.handshake()
                .writeFrame(new Http2HeadersFrame(1, false, postHelloHeaders(getPort())))
                .flush();
            assertThat(handlerStarted.await(5, TimeUnit.SECONDS), equalTo(true));

            var connection = (Http2Connection) server.activeConnections().iterator().next();
            Http2StreamRegistry streamRegistry =
                getField(connection, "streamRegistry", Http2StreamRegistry.class);
            var trailers = new FieldBlock();
            trailers.add("checksum", "x".repeat(1024));
            con.writeFrame(new Http2HeadersFrame(1, true, trailers)).flush();

            var reset = con.readLogicalFrame(Http2ResetStreamFrame.class);
            assertThat(reset.streamId(), equalTo(1));
            assertThat(reset.errorCodeEnum(), equalTo(Http2ErrorCode.PROTOCOL_ERROR));

            releaseHandler.countDown();
            assertThat(exchangeCompleted.await(5, TimeUnit.SECONDS), equalTo(true));
            assertEventually(streamRegistry::isEmpty, equalTo(true));
        } finally {
            releaseHandler.countDown();
        }
    }

    @Test
    void oversizedInitialHeadersCanReceiveAFlowControlledErrorBody() throws Exception {
        server = httpsServer()
            .withMaxHeadersSize(512)
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {
            con.handshake()
                .writeFrame(new Http2Settings(false, 4096, 100, 0, 16384, 32768))
                .flush();
            assertThat(con.readLogicalFrame(), equalTo(Http2Settings.ACK));

            var oversized = getHelloHeaders(getPort());
            oversized.add("x-oversized", "x".repeat(1024));
            con.writeFrame(new Http2HeadersFrame(1, true, oversized)).flush();

            var responseHeaders = con.readLogicalFrame(Http2HeadersFrame.class);
            assertThat(responseHeaders.headers().get(":status"), equalTo("431"));
            assertNothingToRead(con.socket());

            int contentLength = Integer.parseInt(responseHeaders.headers().get("content-length"));
            con.writeFrame(new Http2WindowUpdate(1, contentLength)).flush();
            var body = con.readLogicalFrame(Http2DataFrame.class);
            assertThat(body.toUTF8(), equalTo("431 Request Header Fields Too Large"));
            assertThat(body.endStream(), equalTo(true));
        }
    }

    @Test
    void maxHeaderListSizeIncludesPerFieldOverhead() throws Exception {
        server = httpsServer()
            .withMaxHeadersSize(150)
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {
            con.handshake()
                .writeFrame(new Http2HeadersFrame(1, true, getHelloHeaders(getPort())))
                .flush();

            var responseHeaders = con.readLogicalFrame(Http2HeadersFrame.class);
            assertThat(responseHeaders.headers().get(":status"), equalTo("431"));
        }
    }

    @Test
    void rejectedInitialHeadersDiscardTheRemainingRequestBody() throws Exception {
        server = httpsServer()
            .withMaxHeadersSize(512)
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {
            con.handshake()
                .writeFrame(new Http2Settings(false, 4096, 100, 0, 16384, 32768))
                .flush();
            assertThat(con.readLogicalFrame(), equalTo(Http2Settings.ACK));

            var oversized = postHelloHeaders(getPort());
            oversized.add("x-oversized", "x".repeat(1024));
            con.writeFrame(new Http2HeadersFrame(1, false, oversized))
                .writeFrame(utf8DataFrame(1, true, "discarded"))
                .flush();

            var responseHeaders = con.readLogicalFrame(Http2HeadersFrame.class);
            int contentLength = Integer.parseInt(responseHeaders.headers().get("content-length"));
            con.writeFrame(new Http2WindowUpdate(1, contentLength)).flush();
            var body = con.readLogicalFrame(Http2DataFrame.class);
            assertThat(body.toUTF8(), equalTo("431 Request Header Fields Too Large"));
            assertThat(body.endStream(), equalTo(true));
        }
    }

    @Test
    void rejectedInitialHeadersRespectMaxConcurrentStreams() throws Exception {
        server = httpsServer()
            .withMaxHeadersSize(512)
            .withHttp2Config(Http2ConfigBuilder.http2Enabled().withMaxConcurrentStreams(1))
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {
            con.handshake()
                .writeFrame(new Http2Settings(false, 4096, 100, 0, 16384, 32768))
                .flush();
            assertThat(con.readLogicalFrame(), equalTo(Http2Settings.ACK));

            var firstOversized = postHelloHeaders(getPort());
            firstOversized.add("x-oversized", "x".repeat(1024));
            var secondOversized = postHelloHeaders(getPort());
            secondOversized.add("x-oversized", "x".repeat(1024));
            con.writeFrame(new Http2HeadersFrame(1, false, firstOversized))
                .writeFrame(new Http2HeadersFrame(3, false, secondOversized))
                .flush();

            var responseHeaders = con.readLogicalFrame(Http2HeadersFrame.class);
            assertThat(responseHeaders.streamId(), equalTo(1));
            assertThat(responseHeaders.headers().get(":status"), equalTo("431"));
            assertThat(
                con.readLogicalFrame(),
                equalTo(new Http2ResetStreamFrame(3, Http2ErrorCode.REFUSED_STREAM.code()))
            );
        }
    }

    @Test
    void resettingARejectedRequestRetiresItsCoordinatorState() throws Exception {
        server = httpsServer()
            .withMaxHeadersSize(512)
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {
            con.handshake();

            var oversized = postHelloHeaders(getPort());
            oversized.add("x-oversized", "x".repeat(1024));
            con.writeFrame(new Http2HeadersFrame(1, false, oversized)).flush();
            assertThat(con.readLogicalFrame(Http2HeadersFrame.class).streamId(), equalTo(1));
            assertThat(con.readLogicalFrame(Http2DataFrame.class).streamId(), equalTo(1));

            var connection = (Http2Connection) server.activeConnections().iterator().next();
            Http2WriteCoordinator coordinator = getField(
                connection,
                "writeCoordinator",
                Http2WriteCoordinator.class
            );
            assertThat(coordinator.streamState(1), equalTo(Http2StreamState.HALF_CLOSED_LOCAL));

            byte[] barrierData = {1, 2, 3, 4, 5, 6, 7, 8};
            con.writeFrame(new Http2ResetStreamFrame(1, Http2ErrorCode.CANCEL.code()))
                .writeFrame(new Http2Ping(false, barrierData))
                .flush();
            assertThat(con.readLogicalFrame(Http2Ping.class), equalTo(new Http2Ping(true, barrierData)));
            assertEventually(() -> coordinator.streamState(1), nullValue());
        }
    }

    @Test
    void trailingHeadersMustEndTheStream() throws Exception {
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.POST, "/hello", (request, response, pathParams) -> response.write(request.readBodyAsString()))
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            FieldBlock trailers = new FieldBlock();
            trailers.add("checksum", "abc123");

            con.handshake()
                .writeFrame(new Http2HeadersFrame(1, false, postHelloHeaders(getPort())))
                .writeFrame(utf8DataFrame(1, false, "Hello"))
                .writeFrame(new Http2HeadersFrame(1, false, trailers))
                .flush();

            var reset = con.readLogicalFrame(Http2ResetStreamFrame.class);
            assertThat(reset.streamId(), equalTo(1));
            assertThat(reset.errorCodeEnum(), equalTo(Http2ErrorCode.PROTOCOL_ERROR));

            con.writeFrame(new Http2HeadersFrame(3, false, postHelloHeaders(getPort())))
                .writeFrame(utf8DataFrame(3, true, "Bye"))
                .flush();

            var response = readIgnoringWindowUpdates(con, Http2HeadersFrame.class);
            assertThat(response.streamId(), equalTo(3));
            assertThat(response.headers().get(":status"), equalTo("200"));
            assertThat(readIgnoringWindowUpdates(con, Http2DataFrame.class).toUTF8(), equalTo("Bye"));
        }
    }

    private byte[] headersFrameWithMissingPadLength(int streamId) {
        return new byte[] {
            0x00, 0x00, 0x00,
            0x01,
            0x0C,
            (byte) (streamId >> 24),
            (byte) (streamId >> 16),
            (byte) (streamId >> 8),
            (byte) streamId,
        };
    }

    private byte[] headersFrameWithPaddingLongerThanPayload(int streamId) {
        return new byte[] {
            0x00, 0x00, 0x01,
            0x01,
            0x0D,
            (byte) (streamId >> 24),
            (byte) (streamId >> 16),
            (byte) (streamId >> 8),
            (byte) streamId,
            0x01,
        };
    }

    private int getPort() {
        return server.uri().getPort();
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
