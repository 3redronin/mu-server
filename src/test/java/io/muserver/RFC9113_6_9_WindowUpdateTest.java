package io.muserver;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static io.muserver.MuServerBuilder.httpsServer;
import static io.muserver.RFCTestUtils.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@DisplayName("RFC 9113 6.9 Frame Definitions: WINDOW_UPDATE")
class RFC9113_6_9_WindowUpdateTest {

    private @Nullable MuServer server;

    @Test
    void connectionWindowUpdateOf0IsNotAllowed() throws Exception {
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .start();
        try (var client = new H2Client();
             var con = client.connect(server)) {
            con.handshake()
                .writeFrame(windowUpdate(0, 0))
                .flush();
            var goaway = con.readLogicalFrame(Http2GoAway.class);
            assertThat(goaway.lastStreamId(), equalTo(0));
            assertThat(goaway.errorCodeEnum(), equalTo(Http2ErrorCode.PROTOCOL_ERROR));
        }
    }

    @Test
    void streamWindowUpdateOf0IsNotAllowed() throws Exception {
        var completedStreams = new LinkedBlockingQueue<ResponseInfo>(1);
        server = httpsServer()
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> {
                response.write("Get got");
            })
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addResponseCompleteListener(completedStreams::add)
            .start();
        try (var client = new H2Client();
             var con = client.connect(server)) {
            con.handshake()
                .writeFrame(new Http2HeadersFrame(1, false, postHelloHeaders(getPort())))
                .writeFrame(windowUpdate(1, 0))
                .flush();
            var reset = con.readLogicalFrame(Http2ResetStreamFrame.class);
            assertThat(reset.errorCodeEnum(), equalTo(Http2ErrorCode.PROTOCOL_ERROR));

            // The http exchange is considered failed
            var info = completedStreams.poll(10, TimeUnit.SECONDS);
            assertThat(info, notNullValue());
            assertThat(info.completedSuccessfully(), equalTo(false));

            // new requests still work on the same connection though
            con.writeFrame(new Http2HeadersFrame(3, true, getHelloHeaders(getPort())))
                .flush();
            con.readLogicalFrame(Http2HeadersFrame.class);
            Http2DataFrame getGot = con.readLogicalFrame(Http2DataFrame.class);
            assertThat(getGot.toUTF8(), equalTo("Get got"));

            info = completedStreams.poll(10, TimeUnit.SECONDS);
            assertThat(info, notNullValue());
            assertThat(info.completedSuccessfully(), equalTo(true));
            System.out.println("And now I'm about to close with EOF");
        }
    }

    @Test
    void streamUpdatesCanComeInOnClosedStreams() throws Exception {
        server = httpsServer()
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> {
                response.write("Get got");
            })
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .start();
        try (var client = new H2Client();
             var con = client.connect(server)) {
            con.handshake()
                .writeFrame(new Http2HeadersFrame(1, true, getHelloHeaders(getPort())))
                .flush();
            con.readLogicalFrame(Http2HeadersFrame.class);
            con.readLogicalFrame(Http2DataFrame.class); // the text
            con.readLogicalFrame(Http2DataFrame.class); // EOS
            con.writeFrame(windowUpdate(1, 100))
                .flush();
            assertNothingToRead(con.socket());
        }
    }

    @Test
    void streamUpdatesCanComeInOnHalfClosedRemoteStreams() throws Exception {
        var writeLatch = new CountDownLatch(1);
        server = httpsServer()
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> {
                response.sendChunk("Get got");
                if (writeLatch.await(10, TimeUnit.SECONDS)) {
                    response.sendChunk(" and done.");
                }
            })
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .start();
        try (var client = new H2Client();
             var con = client.connect(server)) {
            con.handshake()
                .writeFrame(new Http2HeadersFrame(1, true, getHelloHeaders(getPort())))
                .flush();
            con.readLogicalFrame(Http2HeadersFrame.class);
            assertThat(con.readLogicalFrame(Http2DataFrame.class).toUTF8(), equalTo("Get got")); // Get got

            // it should be in half-closed (remote) now

            con.writeFrame(windowUpdate(1, 100))
                .flush();

            assertNothingToRead(con.socket());
            writeLatch.countDown();
            assertThat(con.readLogicalFrame(Http2DataFrame.class).toUTF8(), equalTo(" and done.")); // Get got
            assertThat(con.readLogicalFrame(Http2DataFrame.class).endStream(), equalTo(true)); // Get got
        }
    }

    @Test
    void aStreamThatExceedsTheWindowGetsReset() throws Exception {
        server = httpsServer()
            .addHandler(Method.POST, "/hello", (request, response, pathParams) -> {
                try (var in = request.body();
                     var out = response.outputStream()) {
                    var buffer = new byte[1024];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                        out.flush();
                    }
                }
            })
            .withHttp2Config(Http2ConfigBuilder.http2Enabled().withInitialWindowSize(4))
            .start();
        try (var client = new H2Client();
             var con = client.connect(server)) {
            con.handshake()
                .writeFrame(new Http2HeadersFrame(1, false, postHelloHeaders(getPort())))
                // write up to the limit
                .writeFrame(utf8DataFrame(1, false, "Hell"))
                .flush();
            con.readLogicalFrame(Http2HeadersFrame.class);
            assertThat(con.readLogicalFrame(Http2DataFrame.class).toUTF8(), equalTo("Hell")); // the text

            // and now definitely exceed the limit

            con.writeFrame(utf8DataFrame(1, false, "Hello"))
                .flush();
            var reset = con.readLogicalFrame(Http2ResetStreamFrame.class);
            assertThat(reset.errorCodeEnum(), equalTo(Http2ErrorCode.FLOW_CONTROL_ERROR));

            // todo: check that con limit reduced - and send more frames.
        }
    }

    @Test
    void streamWindowUpdatesNotSentUntilHandlerReadsTheBytes() throws Exception {
        var okayToReadLatch = new CountDownLatch(1);
        server = httpsServer()
            .addHandler(Method.POST, "/hello", (request, response, pathParams) -> {
                try (var in = request.body();
                     var out = response.outputStream()) {
                    var buffer = new byte[32];
                    int read;
                    if (okayToReadLatch.await(10, TimeUnit.SECONDS)) {
                        while ((read = in.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                            out.flush();
                        }
                    }
                    System.out.println("Finished");
                }
            })
            .withHttp2Config(Http2ConfigBuilder.http2Enabled().withInitialWindowSize(4))
            .start();
        try (var client = new H2Client();
             var con = client.connect(server)) {
            con.handshake()
                .writeFrame(new Http2HeadersFrame(1, false, postHelloHeaders(getPort())))
                // write up to the limit which is 4
                .writeFrame(utf8DataFrame(1, false, "Hell"))
                .flush();
            con.readLogicalFrame(Http2HeadersFrame.class);

            // there should be no window update yet because we haven't read
            assertNothingToRead(con.socket());

            okayToReadLatch.countDown();

            // We should get a window update because the read consumed the bytes
            assertThat(con.readLogicalFrame(), equalTo(windowUpdate(1, 4)));

            // Currently we get a connection update - we shouldn't as there is no need to soon but that's for a future optimisation
            assertThat(con.readLogicalFrame(), equalTo(windowUpdate(0, 4)));

            // now we can read data that was written
            assertThat(con.readLogicalFrame(Http2DataFrame.class).toUTF8(), equalTo("Hell")); // the text

            // we can now send more bytes
            con.writeFrame(utf8DataFrame(1, true, "o wo")).flush();

            // and get more updates
            assertThat(con.readLogicalFrame(), equalTo(windowUpdate(1, 4)));
            assertThat(con.readLogicalFrame(), equalTo(windowUpdate(0, 4)));

            // along with the data
            assertThat(con.readLogicalFrame(Http2DataFrame.class).toUTF8(), equalTo("o wo")); // the text
            assertThat(con.readLogicalFrame(), equalTo(Http2DataFrame.eos(1)));

            assertNothingToRead(con.socket());
        }
    }

    private static Http2WindowUpdate windowUpdate(int streamId, int windowSizeIncrement) {
        return new Http2WindowUpdate(streamId, windowSizeIncrement);
    }

    /*
    A receiver that receives a flow-controlled frame MUST always account for its contribution against the connection flow-control window, unless the receiver treats this as a connection error (Section 5.4.1). This is necessary even if the frame is in error. The sender counts the frame toward the flow-control window, but if the receiver does not, the flow-control window at the sender and receiver can become different.
     */


    // todo: make Http2BodyInputStream have multiple pending data frames, then cause stream reset, then check connection credit is fully reimbursed

    private int getPort() {
        return server.uri().getPort();
    }

    @AfterEach
    public void stop() {
        if (server != null) server.stop();
    }

}
