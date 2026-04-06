package io.muserver;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static io.muserver.MuServerBuilder.httpsServer;
import static io.muserver.RFCTestUtils.emptyEosDataFrame;
import static io.muserver.RFCTestUtils.goAway;
import static io.muserver.RFCTestUtils.utf8DataFrame;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("RFC 9113 5.4 Error Handling")
class RFC9113_5_4_ErrorHandlingTest {

    private @Nullable MuServer server;

    @Test
    public void theServerGoAwayReportsLastStreamIDThatWasProcessed() throws Exception {

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

            // cause connection error by reusing the same stream ID
            con.writeFrame(new Http2HeadersFrame(1, true, getHelloHeaders()));
            con.flush();

            var goaway = con.readLogicalFrame(Http2GoAway.class);
            assertThat(goaway.lastStreamId(), equalTo(1));

            assertThrows(IOException.class, con::readFrameHeader);
        }

    }

    @Test
    public void theServerGoAwayReportsLastStream0IfNothingHappenedYet() throws Exception {

        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> {
                response.status(202);
            })
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            con.handshake();

            // cause connection error by using invalid stream ID
            con.writeFrame(new Http2HeadersFrame(2, true, getHelloHeaders())).flush();

            var goaway = con.readLogicalFrame(Http2GoAway.class);
            assertThat(goaway.lastStreamId(), equalTo(0));

            assertThrows(IOException.class, con::readFrameHeader);
        }

    }

    @Test
    public void connectionScopedFlowControlErrorsStayConnectionErrorsEvenOnNonZeroStreamIds() throws Exception {

        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.POST, "/hello", (request, response, pathParams) -> {
                Thread.sleep(5000);
                response.status(202);
            })
            .start();

        byte[] sixteenK = new byte[16384];
        try (var client = new H2Client();
             var con = client.connect(server)) {

            con.handshake()
                .writeFrame(new Http2HeadersFrame(1, false, RFCTestUtils.postHelloHeaders(server.uri().getPort())))
                .writeFrame(new Http2DataFrame(1, false, sixteenK, 0, sixteenK.length))
                .writeFrame(new Http2DataFrame(1, false, sixteenK, 0, sixteenK.length))
                .writeFrame(new Http2DataFrame(1, false, sixteenK, 0, sixteenK.length))
                .writeFrame(new Http2DataFrame(1, false, sixteenK, 0, sixteenK.length))
                .flush();

            var goAway = con.readLogicalFrame(Http2GoAway.class);
            assertThat(goAway.lastStreamId(), equalTo(1));
            assertThat(goAway.errorCodeEnum(), equalTo(Http2ErrorCode.FLOW_CONTROL_ERROR));
            assertThrows(IOException.class, con::readFrameHeader);
        }

    }

    @Test
    public void gracefulShutdownKeepsTheFinalGoAwayLastStreamIDStable() throws Exception {

        var goTime = new CountDownLatch(1);
        var started = new CountDownLatch(1);

        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> {
                started.countDown();
                if (!goTime.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to finish request");
                }
                response.write("done");
            })
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            con.handshake()
                .writeFrame(new Http2HeadersFrame(1, true, getHelloHeaders()))
                .flush();

            assertThat(started.await(5, TimeUnit.SECONDS), equalTo(true));

            var stopper = Executors.newSingleThreadExecutor();
            try {
                var stopped = stopper.submit(() -> server.stop());

                assertThat(con.readLogicalFrame(Http2GoAway.class), equalTo(goAway(0x7FFFFFFF, Http2ErrorCode.NO_ERROR)));

                con.writeFrame(new Http2HeadersFrame(3, true, getHelloHeaders())).flush();
                assertThat(con.readLogicalFrame(Http2ResetStreamFrame.class), equalTo(new Http2ResetStreamFrame(3, Http2ErrorCode.REFUSED_STREAM.code())));

                goTime.countDown();

                assertThat(con.readLogicalFrame(Http2HeadersFrame.class).streamId(), equalTo(1));
                assertThat(con.readLogicalFrame(Http2DataFrame.class), equalTo(utf8DataFrame(1, false, "done")));
                assertThat(con.readLogicalFrame(Http2DataFrame.class), equalTo(emptyEosDataFrame(1)));
                assertThat(con.readLogicalFrame(Http2GoAway.class), equalTo(goAway(1, Http2ErrorCode.NO_ERROR)));

                stopped.get(5, TimeUnit.SECONDS);
                assertThrows(IOException.class, con::readFrameHeader);
            } finally {
                stopper.shutdownNow();
            }
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
