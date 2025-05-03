package io.muserver;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.muserver.MuServerBuilder.httpsServer;
import static io.muserver.RFCTestUtils.goAway;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("RFC 9113 5.1 Stream States")
class RFC9113_5_1_StreamStatesTest {

    private @Nullable MuServer server;

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
        return RFCTestUtils.getHelloHeaders(server.uri().getPort());
    }

    @AfterEach
    public void stop() {
        if (server != null) server.stop();
    }

}
