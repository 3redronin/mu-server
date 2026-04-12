package io.muserver;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import static io.muserver.MuServerBuilder.httpsServer;
import static io.muserver.RFCTestUtils.postHelloHeaders;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static scaffolding.MuAssert.assertNotTimedOut;

@DisplayName("RFC 9113 5.2 Flow Control")
class RFC9113_5_2_FlowControlTest {

    private @Nullable MuServer server;

    @Test
    void cancellingAStreamWithUnreadQueuedDataRefundsConnectionCredit() throws Exception {
        var holdLatch = new CountDownLatch(1);

        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.POST, "/hold", (request, response, pathParams) -> {
                assertNotTimedOut("waiting for hold request to finish", holdLatch);
                try {
                    request.readBodyAsString();
                } catch (Exception ignored) {
                }
            })
            .addHandler(Method.POST, "/hello", (request, response, pathParams) -> {
                response.write(request.readBodyAsString());
            })
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            var holdHeaders = postHelloHeaders(getPort());
            holdHeaders.set(":path", "/hold");

            var echoHeaders = postHelloHeaders(getPort());

            byte[] sixteenKb = repeated('a', 16384);
            byte[] lastChunk = repeated('b', 16383);

            con.handshake()
                .writeFrame(new Http2HeadersFrame(1, false, holdHeaders))
                .writeFrame(new Http2DataFrame(1, false, sixteenKb, 0, sixteenKb.length))
                .writeFrame(new Http2DataFrame(1, false, sixteenKb, 0, sixteenKb.length))
                .writeFrame(new Http2DataFrame(1, false, sixteenKb, 0, sixteenKb.length))
                .writeFrame(new Http2DataFrame(1, false, lastChunk, 0, lastChunk.length))
                .flush();

            con.writeFrame(new Http2ResetStreamFrame(1, Http2ErrorCode.CANCEL.code()))
                .writeFrame(new Http2HeadersFrame(3, false, echoHeaders))
                .writeFrame(RFCTestUtils.utf8DataFrame(3, true, "x"))
                .flush();

            var headers = readIgnoringWindowUpdatesAndStreamOneResets(con, Http2HeadersFrame.class);
            assertThat(headers.streamId(), equalTo(3));
            assertThat(headers.headers().get(":status"), equalTo("200"));

            var data = readIgnoringWindowUpdatesAndStreamOneResets(con, Http2DataFrame.class);
            assertThat(data.streamId(), equalTo(3));
            assertThat(data.toUTF8(), equalTo("x"));

            var eos = readIgnoringWindowUpdatesAndStreamOneResets(con, Http2DataFrame.class);
            assertThat(eos, equalTo(Http2DataFrame.eos(3)));
        } finally {
            holdLatch.countDown();
        }
    }

    private static <T extends LogicalHttp2Frame> T readIgnoringWindowUpdatesAndStreamOneResets(H2ClientConnection con, Class<T> clazz) throws Exception {
        while (true) {
            var frame = con.readLogicalFrame();
            if (clazz.isAssignableFrom(frame.getClass())) {
                return clazz.cast(frame);
            }
            if (frame instanceof Http2WindowUpdate) {
                continue;
            }
            if (frame instanceof Http2ResetStreamFrame) {
                var reset = (Http2ResetStreamFrame) frame;
                if (reset.streamId() == 1) {
                    continue;
                }
            }
            throw new IllegalStateException("Expected " + clazz.getName() + ", got " + frame);
        }
    }

    private byte[] repeated(char c, int count) {
        byte[] bytes = new byte[count];
        java.util.Arrays.fill(bytes, (byte) c);
        return bytes;
    }

    private int getPort() {
        return server.uri().getPort();
    }

    @AfterEach
    public void stop() {
        if (server != null) server.stop();
    }
}





