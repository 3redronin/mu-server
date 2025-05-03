package io.muserver;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.muserver.MuServerBuilder.httpsServer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
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
            con.flush();
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
            con.flush();
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
            con.flush();
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

            con.flush();
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
            con.flush();

            assertThat(con.readLogicalFrame(), equalTo(new Http2Settings(false, 4096, 200,65535, 16384, 8192)));
            assertThat(con.readLogicalFrame(), equalTo(Http2Settings.ACK));

            con.writeFrame(Http2Settings.ACK);
            con.writeFrame(goAway(0, Http2ErrorCode.NO_ERROR));
            con.flush();

            // TODO: or should we get a goaway back?
            assertThrows(IOException.class, con::readFrameHeader);
//            assertThat(con.readLogicalFrame(), equalTo(goAway(0, Http2ErrorCode.NO_ERROR)));

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
    public void thereIsAMaxNumberForStreamID() throws Exception {

        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> {
                response.status(202);
            })
            .start();

        try (var client = new H2Client();
             var con = client.connect(server.uri().getPort())) {

            con.handshake();

            con.writeFrame(new Http2HeadersFrame(Integer.MAX_VALUE, true, getHelloHeaders()));
            con.flush();

            con.readLogicalFrame(Http2HeadersFrame.class);

            // can't actually go above the max value as it is restricted to a 31 bit unsigned integer. So this is stopped by reuse rather than being too big.
            con.writeFrame(new Http2HeadersFrame(0xFFFFFFFF, true, getHelloHeaders()));
            con.flush();

            var goaway = con.readLogicalFrame(Http2GoAway.class);
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




    private @NonNull FieldBlock getHelloHeaders() {
        FieldBlock headers = FieldBlock.newWithDate();
        headers.add(":method", "GET");
        headers.add(":scheme", "https");
        headers.add(":path", "/hello");
        headers.add(":authority", "localhost:" + server.uri().getPort());
        return headers;
    }


    @AfterEach
    public void stop() {
        if (server != null) server.stop();
    }

}
