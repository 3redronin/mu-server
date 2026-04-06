package io.muserver;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.muserver.MuServerBuilder.httpsServer;
import static io.muserver.RFCTestUtils.emptyEosDataFrame;
import static io.muserver.RFCTestUtils.getHelloHeaders;
import static io.muserver.RFCTestUtils.goAway;
import static io.muserver.RFCTestUtils.utf8DataFrame;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("RFC 9113 5.4.3 Connection Termination")
class RFC9113_5_4_3_ConnectionTerminationTest {

    private @Nullable MuServer server;

    @Test
    public void afterReceivingGoAwayTheServerStopsAcceptingNewStreamsButFinishesExistingOnes() throws Exception {
        var goTime = new CountDownLatch(1);
        var started = new CountDownLatch(1);

        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> {
                started.countDown();
                assertThat(goTime.await(5, TimeUnit.SECONDS), equalTo(true));
                response.write("done");
            })
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            con.handshake()
                .writeFrame(new Http2HeadersFrame(1, true, getHelloHeaders(server.uri().getPort())))
                .flush();

            assertThat(started.await(5, TimeUnit.SECONDS), equalTo(true));

            con.writeFrame(goAway(0, Http2ErrorCode.NO_ERROR)).flush();
            con.writeFrame(new Http2HeadersFrame(3, true, getHelloHeaders(server.uri().getPort()))).flush();

            assertThat(con.readLogicalFrame(Http2ResetStreamFrame.class), equalTo(new Http2ResetStreamFrame(3, Http2ErrorCode.REFUSED_STREAM.code())));

            goTime.countDown();

            assertThat(con.readLogicalFrame(Http2HeadersFrame.class).streamId(), equalTo(1));
            assertThat(con.readLogicalFrame(Http2DataFrame.class), equalTo(utf8DataFrame(1, false, "done")));
            assertThat(con.readLogicalFrame(Http2DataFrame.class), equalTo(emptyEosDataFrame(1)));
            assertThat(con.readLogicalFrame(Http2GoAway.class), equalTo(goAway(1, Http2ErrorCode.NO_ERROR)));
            assertThrows(IOException.class, con::readFrameHeader);
        }
    }

    @Test
    public void theClientsGoAwayLastStreamIDCannotIncrease() throws Exception {
        var goTime = new CountDownLatch(1);
        var started = new CountDownLatch(1);

        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> {
                started.countDown();
                assertThat(goTime.await(5, TimeUnit.SECONDS), equalTo(true));
                response.write("done");
            })
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            con.handshake()
                .writeFrame(new Http2HeadersFrame(1, true, getHelloHeaders(server.uri().getPort())))
                .flush();

            assertThat(started.await(5, TimeUnit.SECONDS), equalTo(true));

            con.writeFrame(goAway(0, Http2ErrorCode.NO_ERROR))
                .writeFrame(goAway(2, Http2ErrorCode.NO_ERROR))
                .flush();

            var serverGoAway = con.readLogicalFrame(Http2GoAway.class);
            goTime.countDown();
            assertThat(serverGoAway.lastStreamId(), equalTo(1));
            assertThat(serverGoAway.errorCodeEnum(), equalTo(Http2ErrorCode.PROTOCOL_ERROR));
            assertThrows(IOException.class, con::readFrameHeader);
        }
    }

    @AfterEach
    public void stop() {
        if (server != null) server.stop();
    }

}


