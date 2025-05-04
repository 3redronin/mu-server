package io.muserver;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.muserver.MuServerBuilder.httpsServer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("RFC 9113 5.5 Extending HTTP/2")
class RFC9113_5_5_ExtensionsTest {

    private @Nullable MuServer server;

    @Test
    public void unknownConnectionLevelFrameTypesAreIgnored() throws Exception {

        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> {
                response.status(202);
            })
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            con.handshake();

            con.writeFrame(new Http2HeadersFrame(1, true, getHelloHeaders())).flush();
            con.readLogicalFrame(Http2HeadersFrame.class);

            // now send a new type of frame
            con.writeRaw(new byte[] {
                // payload length
                0b00000000,
                0b00000000,
                0b00000100,

                // type - just making this up
                0x33,

                // unused flags
                0b00000000,

                // stream id
                0, 0, 0, 0,

                // dummy data
                1, 2, 3, 4
            });

            // we should still be able to use the connection
            con.writeFrame(new Http2HeadersFrame(3, true, getHelloHeaders())).flush();
            con.readLogicalFrame(Http2HeadersFrame.class);

        }

    }

    @Test
    public void unknownStreamLevelFrameTypesAreIgnoredForExistingStreams() throws Exception {
        var completionLatch = new CountDownLatch(1);
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> {
                if (completionLatch.await(1, TimeUnit.MINUTES)) {
                    response.status(202);
                } else {
                    response.status(500);
                }
            })
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            con.handshake();

            con.writeFrame(new Http2HeadersFrame(1, true, getHelloHeaders())).flush();

            // now send a new type of frame that applies to the active stream
            con.writeRaw(new byte[] {
                // payload length
                0b00000000,
                0b00000000,
                0b00000100,

                // type - just making this up
                0x33,

                // unused flags
                0b00000000,

                // stream id
                0, 0, 0, 1,

                // dummy data
                1, 2, 3, 4
            });

            completionLatch.countDown();
            assertThat(con.readLogicalFrame(Http2HeadersFrame.class).headers().get(":status"), equalTo("202"));

            // we should still be able to use the connection
            con.writeFrame(new Http2HeadersFrame(3, true, getHelloHeaders())).flush();
            con.readLogicalFrame(Http2HeadersFrame.class);

        }

    }

    @Test
    public void unknownStreamLevelFrameTypesAreIgnoredForStreamInitiation() throws Exception {
        // it's not very clear from the spec if this is allowed. We'll allow them, but we won't
        // let stream IDs be reused
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> {
                response.status(202);
            })
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            con.handshake();

            con.writeFrame(new Http2HeadersFrame(1, true, getHelloHeaders())).flush();
            assertThat(con.readLogicalFrame(Http2HeadersFrame.class).headers().get(":status"), equalTo("202"));

            // now send a new type of frame that applies to a new stream
            con.writeRaw(new byte[] {
                // payload length
                0b00000000,
                0b00000000,
                0b00000100,

                // type - just making this up
                0x33,

                // unused flags
                0b00000000,

                // stream id
                0, 0, 0, 3,

                // dummy data
                1, 2, 3, 4
            });


            // we should still be able to use the connection
            con.writeFrame(new Http2HeadersFrame(5, true, getHelloHeaders())).flush();
            assertThat(con.readLogicalFrame(Http2HeadersFrame.class).headers().get(":status"), equalTo("202"));
        }

    }


    @Test
    public void unknownErrorCodesAreAllowed() throws Exception {
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> {
                response.status(202);
            })
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            con.handshake();

            con.writeFrame(new Http2HeadersFrame(1, true, getHelloHeaders())).flush();
            con.readLogicalFrame(Http2HeadersFrame.class);

            // write a new code
            con.writeFrame(new Http2GoAway(0, /* code not in spec */ 0x0FFF, null)).flush();
        }

    }

    @Test
    public void unknownWindowsSettingsAreIgnored() throws Exception {
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> {
                response.status(202);
            })
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            con.handshake();

            con.writeFrame(new Http2HeadersFrame(1, true, getHelloHeaders())).flush();
            con.readLogicalFrame(Http2HeadersFrame.class);

            // now send a settings object with some new settings
            final int headerTableSize = 8192;
            final int maxConcurrentStreams = 1000;
            final int initialWindowSize = 128000;
            final int maxFrameSize = 32768;
            final int maxHeaderListSize = 4000;
            final int anUnknownSetting = 88;
            con.writeRaw(new byte[] {
                // header - 6 settings of 6 bytes each
                0, 0, 6 * 6, 4, 0, 0, 0, 0, 0,
                // setting list: 2 byte identifier followed by 4 byte unsigned int value - let's switch up the ordering too
                0, 3, (byte)((maxConcurrentStreams >> 16) & 0xFF), (byte)((maxConcurrentStreams >> 24) & 0xFF), (byte)((maxConcurrentStreams >> 8) & 0xFF), (byte)(maxConcurrentStreams & 0xFF),
                0, 1, (byte)((headerTableSize >> 16) & 0xFF), (byte)((headerTableSize >> 24) & 0xFF), (byte)((headerTableSize >> 8) & 0xFF), (byte)(headerTableSize & 0xFF),

                // the unknown one
                1, 0, (byte)((anUnknownSetting >> 16) & 0xFF), (byte)((anUnknownSetting >> 24) & 0xFF), (byte)((anUnknownSetting >> 8) & 0xFF), (byte)(anUnknownSetting & 0xFF),

                0, 4, (byte)((initialWindowSize >> 16) & 0xFF), (byte)((initialWindowSize >> 24) & 0xFF), (byte)((initialWindowSize >> 8) & 0xFF), (byte)(initialWindowSize & 0xFF),
                0, 6, (byte)((maxHeaderListSize >> 16) & 0xFF), (byte)((maxHeaderListSize >> 24) & 0xFF), (byte)((maxHeaderListSize >> 8) & 0xFF), (byte)(maxHeaderListSize & 0xFF),
                0, 5, (byte)((maxFrameSize >> 16) & 0xFF), (byte)((maxFrameSize >> 24) & 0xFF), (byte)((maxFrameSize >> 8) & 0xFF), (byte)(maxFrameSize & 0xFF),
            }).flush();

            // we should get ack'd
            var ack = con.readLogicalFrame(Http2Settings.class);
            assertThat(ack.isAck, equalTo(true));

            // we should still be able to use the connection
            con.writeFrame(new Http2HeadersFrame(3, true, getHelloHeaders())).flush();
            con.readLogicalFrame(Http2HeadersFrame.class);

        }

    }


    private @NonNull FieldBlock getHelloHeaders() {
        return RFCTestUtils.getHelloHeaders(server.uri().getPort());
    }

    @AfterEach
    public void stop() {
        if (server != null) server.stop();
    }

}
