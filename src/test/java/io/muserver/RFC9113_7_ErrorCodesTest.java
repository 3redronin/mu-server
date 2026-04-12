package io.muserver;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.EOFException;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static io.muserver.FieldBlockEncoderTest.hexToByteArray;
import static io.muserver.MuServerBuilder.httpsServer;
import static io.muserver.RFCTestUtils.getHelloHeaders;
import static io.muserver.RFCTestUtils.goAway;
import static io.muserver.RFCTestUtils.headersFrame;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("RFC 9113 7 Error Codes")
class RFC9113_7_ErrorCodesTest {

    private @Nullable MuServer server;

    @ParameterizedTest
    @EnumSource(Http2ErrorCode.class)
    void definedErrorCodesRoundTripFromTheirNumericValues(Http2ErrorCode errorCode) {
        assertThat(Http2ErrorCode.fromCode(errorCode.code()), equalTo(errorCode));
    }

    @Test
    void unknownErrorCodesReturnNullWhenDecoded() {
        assertThat(Http2ErrorCode.fromCode(0x7fffffff), equalTo(null));
    }

    @Test
    void connectionErrorsBecomeGoAwayFrames() {
        var frame = Http2Exception.connection(Http2ErrorCode.PROTOCOL_ERROR, "boom").toFrame();

        assertThat(frame, instanceOf(Http2GoAway.class));
        var goAway = (Http2GoAway) frame;
        assertThat(goAway.lastStreamId(), equalTo(0));
        assertThat(goAway.errorCodeEnum(), equalTo(Http2ErrorCode.PROTOCOL_ERROR));
    }

    @Test
    void streamErrorsBecomeResetStreamFrames() {
        var frame = Http2Exception.stream(Http2ErrorCode.FRAME_SIZE_ERROR, "boom", 3).toFrame();

        assertThat(frame, equalTo(new Http2ResetStreamFrame(3, Http2ErrorCode.FRAME_SIZE_ERROR.code())));
    }

    @Test
    void streamErrorsMustHaveNonZeroStreamIds() {
        var ex = assertThrows(IllegalArgumentException.class, () -> {
            throw Http2Exception.stream(Http2ErrorCode.CANCEL, "boom", 0);
        });

        assertThat(ex.getMessage(), equalTo("Stream errors must have a non-zero stream ID"));
    }

    @Test
    void cancelResetsMakeRequestBodyReadsLookLikeClientCancellation() throws Exception {
        var readCredit = new AtomicLong();
        var discardedCredit = new AtomicLong();

        try (var stream = new Http2BodyInputStream(1000, readCredit::addAndGet, discardedCredit::addAndGet)) {
            stream.onStreamReset(new Http2ResetStreamFrame(1, Http2ErrorCode.CANCEL.code()));

            var ex = assertThrows(EOFException.class, stream::read);
            assertThat(ex.getMessage(), equalTo("Client cancelled the request"));
        }

        assertThat(readCredit.get(), equalTo(0L));
        assertThat(discardedCredit.get(), equalTo(0L));
    }

    @Test
    void nonCancelResetsPreserveTheHttp2ErrorCodeInTheReadFailure() throws Exception {
        try (var stream = new Http2BodyInputStream(1000, credit -> {}, credit -> {})) {
            stream.onStreamReset(new Http2ResetStreamFrame(1, Http2ErrorCode.REFUSED_STREAM.code()));

            var ex = assertThrows(IOException.class, stream::read);
            assertThat(ex, instanceOf(IOException.class));
            assertThat(ex.getMessage(), equalTo("Error reading request body: REFUSED_STREAM (7)"));
        }
    }

    @Test
    void refusedStreamErrorsCanBeRetriedOnANewStream() throws Exception {
        var firstStarted = new CountDownLatch(1);
        var letFirstFinish = new CountDownLatch(1);

        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled().withMaxConcurrentStreams(1))
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> {
                firstStarted.countDown();
                assertThat(letFirstFinish.await(5, TimeUnit.SECONDS), equalTo(true));
                response.status(202);
            })
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            con.handshake()
                .writeFrame(new Http2HeadersFrame(1, true, getHelloHeaders(getPort())))
                .writeFrame(new Http2HeadersFrame(3, true, getHelloHeaders(getPort())))
                .flush();

            assertThat(firstStarted.await(5, TimeUnit.SECONDS), equalTo(true));

            var reset = con.readLogicalFrame(Http2ResetStreamFrame.class);
            assertThat(reset.streamId(), equalTo(3));
            assertThat(reset.errorCodeEnum(), equalTo(Http2ErrorCode.REFUSED_STREAM));

            letFirstFinish.countDown();

            var firstResponse = con.readLogicalFrame(Http2HeadersFrame.class);
            assertThat(firstResponse.streamId(), equalTo(1));
            assertThat(firstResponse.headers().get(":status"), equalTo("202"));

            con.writeFrame(new Http2HeadersFrame(5, true, getHelloHeaders(getPort())))
                .flush();

            var retriedResponse = con.readLogicalFrame(Http2HeadersFrame.class);
            assertThat(retriedResponse.streamId(), equalTo(5));
            assertThat(retriedResponse.headers().get(":status"), equalTo("202"));
        }
    }

    @Test
    void compressionErrorsBecomeConnectionErrors() throws Exception {
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            con.handshake()
                .writeRaw(headersFrame(1, true, true, hexToByteArray("823f21")))
                .flush();

            assertThat(con.readLogicalFrame(), equalTo(goAway(0, Http2ErrorCode.COMPRESSION_ERROR)));
            assertThrows(IOException.class, con::readFrameHeader);
        }
    }

    private int getPort() {
        return server.uri().getPort();
    }

    @AfterEach
    public void stop() {
        if (server != null) server.stop();
    }
}




