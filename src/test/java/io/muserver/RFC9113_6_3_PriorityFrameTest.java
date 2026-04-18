package io.muserver;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import static io.muserver.MuServerBuilder.httpsServer;
import static io.muserver.RFCTestUtils.assertNothingToRead;
import static io.muserver.RFCTestUtils.getHelloHeaders;
import static io.muserver.RFCTestUtils.postHelloHeaders;
import static io.muserver.RFCTestUtils.readIgnoringWindowUpdates;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("RFC 9113 6.3 Frame Definitions: PRIORITY")
class RFC9113_6_3_PriorityFrameTest {

    private @Nullable MuServer server;


    @Test
    void priorityFramesAreIgnored() throws Exception {
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

            con.writeFrame(new Http2PriorityFrame(1, true, 2, 10));
            con.writeFrame(new Http2HeadersFrame(1, false, postHelloHeaders(getPort())));
            con.writeFrame(new Http2PriorityFrame(1, true, 2, 10));
            con.writeFrame(new Http2DataFrame(1, true, "Hello".getBytes(StandardCharsets.UTF_8), 0, 5));
            con.writeFrame(new Http2PriorityFrame(1, true, 2, 10));
            con.flush();

            var resp = readIgnoringWindowUpdates(con, Http2HeadersFrame.class);
            assertThat(resp.streamId(), equalTo(1));
            assertThat(resp.headers().get(":status"), equalTo("200"));

            var data = readIgnoringWindowUpdates(con, Http2DataFrame.class);
            assertThat(data.streamId(), equalTo(1));
            assertThat(new String(data.payload(), data.payloadOffset(), data.payloadLength(), StandardCharsets.UTF_8), equalTo("Hello"));

            var eos = readIgnoringWindowUpdates(con, Http2DataFrame.class);
            assertThat(eos.endStream(), equalTo(true));
        }

    }

    @Test
    void priorityFramesMustBeAssociatedWithAStream() throws Exception {
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            con.handshake();

            con.writeFrame(new Http2PriorityFrame(0, true, 2, 10));
            con.flush();

            assertThat(con.readLogicalFrame(), equalTo(RFCTestUtils.goAway(0, Http2ErrorCode.PROTOCOL_ERROR)));
            assertThrows(IOException.class, con::readFrameHeader);
        }

    }

    @Test
    void priorityFramesOnIdleStreamsAreIgnoredWithoutCreatingStreams() throws Exception {
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> response.status(202))
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            con.handshake()
                .writeFrame(new Http2PriorityFrame(3, false, 0, 10))
                .flush();

            assertNothingToRead(con.socket());

            con.writeFrame(new Http2HeadersFrame(3, true, getHelloHeaders(getPort())))
                .flush();

            var response = con.readLogicalFrame(Http2HeadersFrame.class);
            assertThat(response.streamId(), equalTo(3));
            assertThat(response.headers().get(":status"), equalTo("202"));
        }
    }

    @Test
    void priorityFramesOnClosedStreamsAreIgnored() throws Exception {
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> response.status(202))
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            con.handshake()
                .writeFrame(new Http2HeadersFrame(1, true, getHelloHeaders(getPort())))
                .flush();

            var response = con.readLogicalFrame(Http2HeadersFrame.class);
            assertThat(response.streamId(), equalTo(1));
            assertThat(response.headers().get(":status"), equalTo("202"));

            con.writeFrame(new Http2PriorityFrame(1, false, 0, 10))
                .flush();

            assertNothingToRead(con.socket());

            con.writeFrame(new Http2HeadersFrame(3, true, getHelloHeaders(getPort())))
                .flush();

            var nextResponse = con.readLogicalFrame(Http2HeadersFrame.class);
            assertThat(nextResponse.streamId(), equalTo(3));
            assertThat(nextResponse.headers().get(":status"), equalTo("202"));
        }
    }

    @Test
    void priorityFramesMustNotDependOnThemselves() throws Exception {
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            con.handshake()
                .writeFrame(new Http2PriorityFrame(3, false, 3, 10))
                .flush();

            var reset = con.readLogicalFrame(Http2ResetStreamFrame.class);
            assertThat(reset.streamId(), equalTo(3));
            assertThat(reset.errorCodeEnum(), equalTo(Http2ErrorCode.PROTOCOL_ERROR));
        }
    }

    @Test
    void priorityFramesWithInvalidLengthAreStreamErrors() throws Exception {
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> response.status(202))
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            con.handshake()
                .writeRaw(priorityFrameWithPayloadLength(1, 4))
                .flush();

            var reset = con.readLogicalFrame(Http2ResetStreamFrame.class);
            assertThat(reset.streamId(), equalTo(1));
            assertThat(reset.errorCodeEnum(), equalTo(Http2ErrorCode.FRAME_SIZE_ERROR));

            con.writeFrame(new Http2HeadersFrame(3, true, getHelloHeaders(getPort())))
                .flush();

            var response = con.readLogicalFrame(Http2HeadersFrame.class);
            assertThat(response.streamId(), equalTo(3));
            assertThat(response.headers().get(":status"), equalTo("202"));
        }
    }

    private static byte[] priorityFrameWithPayloadLength(int streamId, int payloadLength) {
        byte[] frame = new byte[9 + payloadLength];
        frame[0] = (byte) (payloadLength >> 16);
        frame[1] = (byte) (payloadLength >> 8);
        frame[2] = (byte) payloadLength;
        frame[3] = 0x02;
        frame[4] = 0;
        frame[5] = (byte) (streamId >> 24);
        frame[6] = (byte) (streamId >> 16);
        frame[7] = (byte) (streamId >> 8);
        frame[8] = (byte) streamId;
        return frame;
    }

    private int getPort() {
        return server.uri().getPort();
    }

    @AfterEach
    public void stop() {
        if (server != null) server.stop();
    }

    static class Http2PriorityFrame implements LogicalHttp2Frame {
        private final int streamId;
        private final boolean exclusive;
        private final int streamDependencyId;
        private final int weight;

        Http2PriorityFrame(int streamId, boolean exclusive, int streamDependencyId, int weight) {
            this.streamId = streamId;
            this.exclusive = exclusive;
            this.streamDependencyId = streamDependencyId;
            this.weight = weight;
        }

        public int streamId() {
            return streamId;
        }

        @Override
        public void writeTo(Http2Peer connection, OutputStream out) throws IOException {
            int dependency = exclusive ? (streamDependencyId | 0x80000000) : streamDependencyId;
            out.write(new byte[] {
                // payload length - always 5
                0b00000000,
                0b00000000,
                0b00000101,

                // type
                0x02,

                // unused flags
                0b00000000,

                // stream id
                (byte) (streamId >> 24),
                (byte) (streamId >> 16),
                (byte) (streamId >> 8),
                (byte) streamId,

                // exclusive bit + stream dependency id
                (byte) (dependency >> 24),
                (byte) (dependency >> 16),
                (byte) (dependency >> 8),
                (byte) dependency,

                // weight
                (byte)weight
            });
        }

    }
}
