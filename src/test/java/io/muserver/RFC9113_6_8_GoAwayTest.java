package io.muserver;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static io.muserver.MuServerBuilder.httpsServer;
import static io.muserver.RFCTestUtils.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static scaffolding.MuAssert.assertNotTimedOut;

@DisplayName("RFC 9113 6.8 Frame Definitions: GO_AWAY")
class RFC9113_6_8_GoAwayTest {

    private @Nullable MuServer server;

    @Test
    void aGracefulShutdownOnIdleConnectionSendsAGoAway() throws Exception {
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .start();
        try (var client = new H2Client();
             var con = client.connect(server)) {
            con.handshake();
            assertNothingToRead(con.socket());
            var stopper = Executors.newSingleThreadExecutor();
            try {
                var stopped = stopper.submit(() -> server.stop());
                assertThat("Expected warning goaway", con.readLogicalFrame(),
                    equalTo(goAway(0x7FFFFFFF, Http2ErrorCode.NO_ERROR)));
                assertThat("Expected final goaway", con.readLogicalFrame(),
                    equalTo(goAway(0, Http2ErrorCode.NO_ERROR)));
                stopped.get(5, TimeUnit.SECONDS);
                assertThrows(IOException.class, con::readFrameHeader);
            } finally {
                stopper.shutdownNow();
            }
        }
    }

    @Test
    void aGracefulShutdownAllowsInFlightStreamCreationBeforeSendingTheFinalGoAway() throws Exception {
        var goTime = new CountDownLatch(1);
        var twoRequestsStartedLatch = new CountDownLatch(2);
        var threeRequestsStartedLatch = new CountDownLatch(3);
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> {
                twoRequestsStartedLatch.countDown();
                threeRequestsStartedLatch.countDown();
                if (goTime.await(5, TimeUnit.SECONDS)) {
                    response.write("done");
                } else {
                    response.write("timed out");
                }
            })
            .start();
        try (var client = new H2Client();
             var con = client.connect(server)) {
            con.handshake();

            con.writeFrame(getHelloFrame(1))
                .writeFrame(getHelloFrame(3))
                .flush();

            assertNotTimedOut("Waiting for 2 requests to start", twoRequestsStartedLatch);

            var stopper = Executors.newSingleThreadExecutor();
            var stopped = stopper.submit(() -> server.stop());

            assertThat("Expected warning goaway", con.readLogicalFrame(),
                equalTo(goAway(0x7FFFFFFF, Http2ErrorCode.NO_ERROR)));

            con.writeFrame(getHelloFrame(5)).flush();
            assertNotTimedOut("Waiting for grace-period stream to start", threeRequestsStartedLatch);

            assertThat("Expected final goaway", con.readLogicalFrame(),
                equalTo(goAway(5, Http2ErrorCode.NO_ERROR)));

            con.writeFrame(getHelloFrame(7)).flush();
            assertThat(con.readLogicalFrame(), equalTo(new Http2ResetStreamFrame(7, Http2ErrorCode.REFUSED_STREAM.code())));

            goTime.countDown();

            var nextFrames = List.of(
                con.readLogicalFrame(), con.readLogicalFrame(), con.readLogicalFrame(),
                con.readLogicalFrame(), con.readLogicalFrame(), con.readLogicalFrame(),
                con.readLogicalFrame(), con.readLogicalFrame(), con.readLogicalFrame()
            );
            assertThat(nextFrames, containsInAnyOrder(
                instanceOf(Http2HeadersFrame.class), instanceOf(Http2DataFrame.class), instanceOf(Http2DataFrame.class),
                instanceOf(Http2HeadersFrame.class), instanceOf(Http2DataFrame.class), instanceOf(Http2DataFrame.class),
                instanceOf(Http2HeadersFrame.class), instanceOf(Http2DataFrame.class), instanceOf(Http2DataFrame.class)
                ));

            assertThat(nextFrames.stream().filter(f -> f instanceof Http2DataFrame).collect(Collectors.toList()),
                containsInAnyOrder(
                    utf8DataFrame(1, false, "done"),
                    emptyEosDataFrame(1),
                    utf8DataFrame(3, false, "done"),
                    emptyEosDataFrame(3),
                    utf8DataFrame(5, false, "done"),
                    emptyEosDataFrame(5)
                ));

            stopped.get(5, TimeUnit.SECONDS);
            assertThrows(IOException.class, con::readFrameHeader);

            stopper.shutdownNow();
        }
    }

    @Test
    void goAwayFramesMustHaveAtLeastEightBytesOfPayload() throws Exception {
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {
            con.handshake();

            con.writeRaw(rawGoAwayFrame(0, new byte[7])).flush();

            assertThat(con.readLogicalFrame(), equalTo(goAway(0, Http2ErrorCode.FRAME_SIZE_ERROR)));
            assertThrows(IOException.class, con::readFrameHeader);
        }
    }

    @Test
    void goAwayFramesMustBeOnStreamZero() throws Exception {
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {
            con.handshake();

            con.writeRaw(rawGoAwayFrame(1, ByteBuffer.allocate(8).putInt(0).putInt(0).array())).flush();

            assertThat(con.readLogicalFrame(), equalTo(goAway(0, Http2ErrorCode.PROTOCOL_ERROR)));
            assertThrows(IOException.class, con::readFrameHeader);
        }
    }

    private @NonNull Http2HeadersFrame getHelloFrame(int streamId) {
        return new Http2HeadersFrame(streamId, true, getHelloHeaders(getPort()));
    }


    private int getPort() {
        return server.uri().getPort();
    }

    private byte[] rawGoAwayFrame(int streamId, byte[] payload) {
        byte[] frame = new byte[9 + payload.length];
        frame[0] = (byte) (payload.length >> 16);
        frame[1] = (byte) (payload.length >> 8);
        frame[2] = (byte) payload.length;
        frame[3] = 0x07;
        frame[4] = 0;
        frame[5] = (byte) (streamId >> 24);
        frame[6] = (byte) (streamId >> 16);
        frame[7] = (byte) (streamId >> 8);
        frame[8] = (byte) streamId;
        System.arraycopy(payload, 0, frame, 9, payload.length);
        return frame;
    }

    @AfterEach
    public void stop() {
        if (server != null) server.stop();
    }

}
