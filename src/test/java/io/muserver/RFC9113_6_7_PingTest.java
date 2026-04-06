package io.muserver;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static io.muserver.MuServerBuilder.httpsServer;
import static io.muserver.RFCTestUtils.assertNothingToRead;
import static io.muserver.RFCTestUtils.goAway;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("RFC 9113 6.7 Frame Definitions: PING")
class RFC9113_6_7_PingTest {

    private @Nullable MuServer server;

    @Test
    void ordinaryPingFramesAreAckedWithIdenticalOpaqueData() throws Exception {
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            byte[] opaqueData = new byte[] {0, 1, 2, 3, 4, 5, 6, 7};

            con.handshake()
                .writeFrame(new Http2Ping(false, opaqueData))
                .flush();

            assertThat(con.readLogicalFrame(Http2Ping.class), equalTo(new Http2Ping(true, opaqueData)));
        }
    }

    @Test
    void pingAckFramesDoNotTriggerReplies() throws Exception {
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            con.handshake()
                .writeFrame(new Http2Ping(true, new byte[] {9, 8, 7, 6, 5, 4, 3, 2}))
                .flush();

            assertNothingToRead(con.socket());
        }
    }

    @Test
    void pingFramesMustBeOnStream0() throws Exception {
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            con.handshake()
                .writeRaw(rawPingFrame(0, 1, new byte[] {0, 1, 2, 3, 4, 5, 6, 7}))
                .flush();

            assertThat(con.readLogicalFrame(), equalTo(goAway(0, Http2ErrorCode.PROTOCOL_ERROR)));
            assertThrows(IOException.class, con::readFrameHeader);
        }
    }

    @Test
    void pingFramesMustBeEightBytesLong() throws Exception {
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            con.handshake()
                .writeRaw(rawPingFrame(0, 0, new byte[] {0, 1, 2, 3, 4, 5, 6}))
                .flush();

            assertThat(con.readLogicalFrame(), equalTo(goAway(0, Http2ErrorCode.FRAME_SIZE_ERROR)));
            assertThrows(IOException.class, con::readFrameHeader);
        }
    }

    private static byte[] rawPingFrame(int flags, int streamId, byte[] opaqueData) {
        byte[] frame = new byte[9 + opaqueData.length];
        frame[0] = (byte) (opaqueData.length >> 16);
        frame[1] = (byte) (opaqueData.length >> 8);
        frame[2] = (byte) opaqueData.length;
        frame[3] = 0x06;
        frame[4] = (byte) flags;
        frame[5] = (byte) (streamId >> 24);
        frame[6] = (byte) (streamId >> 16);
        frame[7] = (byte) (streamId >> 8);
        frame[8] = (byte) streamId;
        System.arraycopy(opaqueData, 0, frame, 9, opaqueData.length);
        return frame;
    }

    @AfterEach
    public void stop() {
        if (server != null) server.stop();
    }
}
