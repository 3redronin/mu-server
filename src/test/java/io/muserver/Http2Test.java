package io.muserver;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static io.muserver.MuServerBuilder.httpsServer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Http2Test {

    private @Nullable MuServer server;

    @Test
    public void anInvalidPrefaceLeadsToConnectionError() throws Exception {
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .start();
        try (var client = new H2Client();
             var con = client.connect(server.uri().getPort())) {
            con.writeRaw("This is not a valid preface".getBytes(StandardCharsets.US_ASCII));
            con.flushOutput();
            assertThat(con.readLogicalFrame(), equalTo(goAway(0, Http2ErrorCode.PROTOCOL_ERROR)));
            assertThrows(IOException.class, con::readFrameHeader);
        }
    }

    @Test
    public void anInvalidFirstFrameLeadsToConnectionError() throws Exception {
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .start();
        try (var client = new H2Client();
             var con = client.connect(server.uri().getPort())) {
            con.writePreface();
            con.writeFrame(new Http2DataFrame(1, true, new byte[0], 0, 0));
            con.flushOutput();
            assertThat(con.readLogicalFrame(), equalTo(goAway(0, Http2ErrorCode.PROTOCOL_ERROR)));
            assertThrows(IOException.class, con::readFrameHeader);
        }
    }

    @Test
    public void connectionErrorIfFrameSizeIsTooSmall() throws Exception {
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .start();
        try (var client = new H2Client();
             var con = client.connect(server.uri().getPort())) {
            con.writePreface();
            // settings frames are of a size a multiple of 6. Here we say this is a settings frame of size 3, which is invalid
            con.writeRaw(new byte[] {
                0, 0, 3, 4, 0, 0, 0, 0, 0,
                0, 1, 0});
            con.flushOutput();
            assertThat(con.readLogicalFrame(), equalTo(goAway(0, Http2ErrorCode.FRAME_SIZE_ERROR)));
            assertThrows(IOException.class, con::readFrameHeader);
        }
    }

    @Test
    public void connectionErrorIfFrameSizeIsTooLarge() throws Exception {
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .start();
        try (var client = new H2Client();
             var con = client.connect(server.uri().getPort())) {
            con.writePreface();
            int payloadLength = 16385;
            con.writeRaw(new byte[] {
                // len
                (byte)(payloadLength >> 16),
                (byte)(payloadLength >> 8),
                (byte)payloadLength,
                // type
                (byte)0,
                // flags
                0,
                // stream id
                (byte)0,
                (byte)0,
                (byte)0,
                (byte)0
            });

            con.flushOutput();
            assertThat(con.readLogicalFrame(), equalTo(goAway(0, Http2ErrorCode.FRAME_SIZE_ERROR)));
            assertThrows(IOException.class, con::readFrameHeader);
        }
    }


    private static Http2GoAway goAway(int lastStreamId, Http2ErrorCode code) {
        return new Http2GoAway(lastStreamId, code.code(), new byte[0]);
    }


    @Test
    public void settingsAreAckedOnStart() throws Exception {

        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .start();

        try (var client = new H2Client();
             var con = client.connect(server.uri().getPort())) {

            con.writePreface();
            con.writeFrame(Http2Settings.DEFAULT_CLIENT_SETTINGS);
            con.flushOutput();

            assertThat(con.readLogicalFrame(), equalTo(new Http2Settings(false, 4096, 200,65535, 16384, 8192)));
            assertThat(con.readLogicalFrame(), equalTo(Http2Settings.ACK));

            con.writeFrame(Http2Settings.ACK);
            con.writeFrame(goAway(0, Http2ErrorCode.NO_ERROR));
            con.flushOutput();

            // TODO: or should we get a goaway back?
            assertThrows(IOException.class, con::readFrameHeader);
//            assertThat(con.readLogicalFrame(), equalTo(goAway(0, Http2ErrorCode.NO_ERROR)));

        }

    }

    @AfterEach
    public void stop() {
        if (server != null) server.stop();
    }

}
