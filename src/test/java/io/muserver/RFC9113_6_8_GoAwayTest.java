package io.muserver;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import scaffolding.MuAssert;

import java.io.EOFException;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
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
            server.stop();
            assertThat("Expected warning goaway", con.readLogicalFrame(),
                equalTo(goAway(0x7FFFFFFF, Http2ErrorCode.NO_ERROR)));
            assertThat("Expected final goaway", con.readLogicalFrame(),
                equalTo(goAway(0, Http2ErrorCode.NO_ERROR)));
        }
    }

    @Test
    void aGracefulShutdownLetsRemainingRequestsComplete() throws Exception {
        var goTime = new CountDownLatch(1);
        var twoRequestsStartedLatch = new CountDownLatch(2);
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> {
                twoRequestsStartedLatch.countDown();
                System.out.println("Request started");
                if (goTime.await(5, TimeUnit.SECONDS)) {
                    System.out.println("Wrotten");
                    response.write("done");
                } else {
                    System.out.println("Timed out");
                    response.write("timed out");
                }
                System.out.println("Response complete");
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
            stopper.submit(() -> server.stop());

            System.out.println("server stopped");
            assertThat("Expected warning goaway", con.readLogicalFrame(),
                equalTo(goAway(0x7FFFFFFF, Http2ErrorCode.NO_ERROR)));
            System.out.println("Warning gotten");
            assertThat("Expected final goaway", con.readLogicalFrame(),
                equalTo(goAway(3, Http2ErrorCode.NO_ERROR)));
            System.out.println("Final gotten");
            con.writeFrame(getHelloFrame(5)).flush();
            System.out.println("Client sent stream 5");
            assertThat(con.readLogicalFrame(), equalTo(new Http2ResetStreamFrame(5, Http2ErrorCode.REFUSED_STREAM.code())));

            goTime.countDown();

            var nextFrames = List.of(
                con.readLogicalFrame(), con.readLogicalFrame(), con.readLogicalFrame(), con.readLogicalFrame(), con.readLogicalFrame(), con.readLogicalFrame()
            );
            assertThat(nextFrames, containsInAnyOrder(
                instanceOf(Http2HeadersFrame.class), instanceOf(Http2DataFrame.class), instanceOf(Http2DataFrame.class),
                instanceOf(Http2HeadersFrame.class), instanceOf(Http2DataFrame.class), instanceOf(Http2DataFrame.class)
                ));

            assertThat(nextFrames.stream().filter(f -> f instanceof Http2DataFrame).collect(Collectors.toList()),
                containsInAnyOrder(
                    utf8DataFrame(1, false, "done"),
                    emptyEosDataFrame(1),
                    utf8DataFrame(3, false, "done"),
                    emptyEosDataFrame(3)
                ));


            stopper.shutdownNow();
        }
    }

    private @NonNull Http2HeadersFrame getHelloFrame(int streamId) {
        return new Http2HeadersFrame(streamId, true, getHelloHeaders(getPort()));
    }


    private int getPort() {
        return server.uri().getPort();
    }

    @AfterEach
    public void stop() {
        if (server != null) server.stop();
    }

}
