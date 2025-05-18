package io.muserver;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static io.muserver.MuServerBuilder.httpsServer;
import static io.muserver.RFCTestUtils.postHelloHeaders;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static scaffolding.MuAssert.assertNotTimedOut;

@DisplayName("RFC 9113 6.1 Frame Definitions: DATA")
class RFC9113_6_1_DataFrameTest {

    private @Nullable MuServer server;

    @Test
    void dataFrameSerialisationTest() throws Exception {
        roundTrip(new Http2DataFrame(1, false, new byte[0], 0, 0));
        roundTrip(new Http2DataFrame(2, true, "Hello".getBytes(StandardCharsets.UTF_8), 0, 5));
    }

    private void roundTrip(Http2DataFrame example) throws IOException, Http2Exception {
        var baos = new ByteArrayOutputStream();
        example.writeTo(null, baos);
        var buffer = ByteBuffer.wrap(baos.toByteArray());
        var header = Http2FrameHeader.readFrom(buffer);
        var recreated = Http2DataFrame.readFrom(header, buffer);
        assertThat(recreated, equalTo(example));
    }

    @ParameterizedTest
    // from spec: "Note: A frame can be increased in size by one octet by including a Pad Length field with a value of zero."
    @ValueSource(ints = {0, 1, 255})
    void dataFramesCanHavePaddingWhichIsIgnored(int paddingLength) throws Exception {
        var baos = new ByteArrayOutputStream();

        var data = "This is a payload".getBytes(StandardCharsets.UTF_8);

        var payloadLength = 1 /* padding length declaration */ + data.length + paddingLength;
        var streamId = 3;

        baos.write(new byte[] {
            // len
            (byte)(payloadLength >> 16),
            (byte)(payloadLength >> 8),
            (byte)payloadLength,
            // type data
            (byte)0,
            // flags - padding bit and EOS and unused set
            0b00101001,
            // stream id
            (byte)(streamId >> 24),
            (byte)(streamId >> 16),
            (byte)(streamId >> 8),
            (byte)(streamId),
        });

        baos.write(paddingLength);
        baos.write(data);
        baos.write(new byte[paddingLength]);

        var buffer = ByteBuffer.wrap(baos.toByteArray());
        var header = Http2FrameHeader.readFrom(buffer);
        var recreated = Http2DataFrame.readFrom(header, buffer);
        assertThat(recreated, equalTo(new Http2DataFrame(streamId, true, data, 0, data.length)));

    }

    @Test
    void ifPaddingLengthIsGreaterThanOrEqualToPayloadLengthThatIsAConnectionError() throws Exception {
        var baos = new ByteArrayOutputStream();

        var data = "This is a payload".getBytes(StandardCharsets.UTF_8);

        int paddingLength = 10;

        var payloadLength = 1 /* padding length declaration */ + data.length + paddingLength;

        int declaredPaddingLength = payloadLength;

        var streamId = 3;

        baos.write(new byte[] {
            // len
            (byte)(payloadLength >> 16),
            (byte)(payloadLength >> 8),
            (byte)payloadLength,
            // type data
            (byte)0,
            // flags - padding bit and EOS and unused set
            0b00101001,
            // stream id
            (byte)(streamId >> 24),
            (byte)(streamId >> 16),
            (byte)(streamId >> 8),
            (byte)(streamId),
        });

        baos.write(declaredPaddingLength);
        baos.write(data);
        baos.write(new byte[paddingLength]);

        var buffer = ByteBuffer.wrap(baos.toByteArray());
        var header = Http2FrameHeader.readFrom(buffer);
        var ex = assertThrows(Http2Exception.class, () -> Http2DataFrame.readFrom(header, buffer));
        assertThat(ex.errorType(), equalTo(Http2Level.CONNECTION));
        assertThat(ex.errorCode(), equalTo(Http2ErrorCode.PROTOCOL_ERROR));

    }

    @Test
    void aSingleDataFrameCanBeSent() throws Exception {
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.POST, "/hello", (request, response, pathParams) -> {
                response.status(200);
                response.headers().set("content-type", request.contentType());
                response.write(request.readBodyAsString());
            })
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            con.handshake();

            con.writeFrame(new Http2HeadersFrame(1, false, postHelloHeaders(getPort())));
            con.writeFrame(new Http2DataFrame(1, true, "Hello".getBytes(StandardCharsets.UTF_8), 0, 5));
            con.flush();

            var resp = con.readLogicalFrame(Http2HeadersFrame.class);
            assertThat(resp.streamId(), equalTo(1));
            assertThat(resp.headers().get(":status"), equalTo("200"));
            assertThat(resp.headers().get("content-type"), equalTo("text/plain; charset=utf-8"));
            assertThat(resp.endStream(), equalTo(false));

            var data = con.readLogicalFrame(Http2DataFrame.class);
            assertThat(data.streamId(), equalTo(1));
            assertThat(data.flowControlSize(), equalTo(5));
            assertThat(new String(data.payload(), data.payloadOffset(), data.payloadLength(), StandardCharsets.UTF_8), equalTo("Hello"));

            // note: the end stream is always separate currently, as doing things like gzipping is much easier when this is the case
            assertThat(data.endStream(), equalTo(false));
            var eos = con.readLogicalFrame(Http2DataFrame.class);
            assertThat(eos.endStream(), equalTo(true));
            assertThat(eos.streamId(), equalTo(1));
            assertThat(eos.flowControlSize(), equalTo(0));
            assertThat(eos.payloadLength(), equalTo(0));
        }

    }

    @Test
    void aDataFrameReceivedOnAClosedStreamIsStreamError() throws Exception {
        var completedLatch = new CountDownLatch(1);
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.POST, "/hello", (request, response, pathParams) -> {
                response.status(200);
                response.headers().set("content-type", request.contentType());
                response.write(request.readBodyAsString());
            })
            .addResponseCompleteListener(info -> {
                // we know the request and response has fully completed; the stream status is closed
                completedLatch.countDown();
            })
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            con.handshake()
                .writeFrame(new Http2HeadersFrame(1, false, postHelloHeaders(getPort())))
                .writeFrame(new Http2DataFrame(1, true, "Hello".getBytes(StandardCharsets.UTF_8), 0, 5))
                .flush();
            con.readLogicalFrame(Http2HeadersFrame.class);
            con.readLogicalFrame(Http2DataFrame.class);
            con.readLogicalFrame(Http2DataFrame.class);

            assertNotTimedOut("Waiting for stream to complete", completedLatch);

            // The client stream is now fully closed so we aren't allowed to send more data. So we do.
            con.writeFrame(new Http2DataFrame(1, true, "More".getBytes(StandardCharsets.UTF_8), 0, 4))
                .flush();

            var reset = con.readLogicalFrame(Http2ResetStreamFrame.class);
            assertThat(reset.streamId(), equalTo(1));
            assertThat(reset.errorCodeEnum(), equalTo(Http2ErrorCode.STREAM_CLOSED));
        }

    }

    @Test
    void aDataFrameReceivedOnARemoteClosedStreamIsStreamError() throws Exception {
        var okayToCloseLocal = new CountDownLatch(1);
        var completedStreams = new LinkedBlockingQueue<ResponseInfo>(1);
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.POST, "/hello", (request, response, pathParams) -> {
                response.status(200);
                response.headers().set("content-type", request.contentType());
                // Do not write the output, otherwise we would move to closed-local or closed.
                // Rather, we want the request to be complete but not the response.
                if (okayToCloseLocal.await(10, TimeUnit.SECONDS)) {
                    response.write(request.readBodyAsString());
                }
            })
            .addResponseCompleteListener(completedStreams::add)
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            con.handshake()
                .writeFrame(new Http2HeadersFrame(1, false, postHelloHeaders(getPort())))
                .writeFrame(new Http2DataFrame(1, true, "Hello".getBytes(StandardCharsets.UTF_8), 0, 5))
                // The client stream is now half-closed local; on the server it is half-closed remote.
                // The client isn't allowed to send more data, so let's do that.
                .writeFrame(new Http2DataFrame(1, true, "More".getBytes(StandardCharsets.UTF_8), 0, 4))
                .flush();

            var reset = con.readLogicalFrame(Http2ResetStreamFrame.class);
            assertThat(reset.streamId(), equalTo(1));
            assertThat(reset.errorCodeEnum(), equalTo(Http2ErrorCode.STREAM_CLOSED));

            okayToCloseLocal.countDown();

            var info = completedStreams.poll(10, TimeUnit.SECONDS);
            assertThat(info, notNullValue());
            assertThat(info.completedSuccessfully(), equalTo(false));

            // wanna assert that nothing is coming
            assertThat(con.available(), equalTo(0));
            RFCTestUtils.assertNothingToRead(con.socket());

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
