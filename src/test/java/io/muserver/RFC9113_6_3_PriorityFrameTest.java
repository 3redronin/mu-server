package io.muserver;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static io.muserver.MuServerBuilder.httpsServer;
import static io.muserver.RFCTestUtils.postHelloHeaders;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static scaffolding.MuAssert.assertNotTimedOut;

@DisplayName("RFC 9113 6.3 Frame Definitions: PRIORITY")
class RFC9113_6_3_PriorityFrameTest {

    private @Nullable MuServer server;


    @Test
    void priorityFramesAreIgnored() throws Exception {
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.POST, "/hello", (request, response, pathParams) -> {
                response.status(200);
                response.headers().set("content-type", request.contentType());
                response.write(request.readBodyAsString());
            })
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            con.handshake();

            con.writeFrame(new Http2PriorityFrame(1, true, 2, 10));
            con.writeFrame(new Http2HeadersFrame(1, false, postHelloHeaders(getPort())));
            con.writeFrame(new Http2PriorityFrame(1, true, 2, 10));
            con.writeFrame(new Http2DataFrame(1, true, "Hello".getBytes(StandardCharsets.UTF_8), 0, 5));
            con.writeFrame(new Http2PriorityFrame(1, true, 2, 10));
            con.flush();

            var resp = con.readLogicalFrame(Http2HeadersFrame.class);
            assertThat(resp.streamId(), equalTo(1));
            assertThat(resp.headers().get(":status"), equalTo("200"));

            var data = con.readLogicalFrame(Http2DataFrame.class);
            assertThat(data.streamId(), equalTo(1));
            assertThat(new String(data.payload(), data.payloadOffset(), data.payloadLength(), StandardCharsets.UTF_8), equalTo("Hello"));

            var eos = con.readLogicalFrame(Http2DataFrame.class);
            assertThat(eos.endStream(), equalTo(true));
        }

    }

    private int getPort() {
        return server.uri().getPort();
    }

    @AfterEach
    public void stop() {
        if (server != null) server.stop();
    }

    static class Http2PriorityFrame implements LogicalHttp2Frame {
        private final int streamId;
        private final boolean exclusive;
        private final int streamDependencyId;
        private final int weight;

        Http2PriorityFrame(int streamId, boolean exclusive, int streamDependencyId, int weight) {
            this.streamId = streamId;
            this.exclusive = exclusive;
            this.streamDependencyId = streamDependencyId;
            this.weight = weight;
        }

        public int streamId() {
            return streamId;
        }

        public boolean exclusive() {
            return exclusive;
        }

        public int streamDependencyId() {
            return streamDependencyId;
        }

        public int weight() {
            return weight;
        }

        @Override
        public void writeTo(Http2Peer connection, OutputStream out) throws IOException {
            int exclusiveMask = exclusive ? 0xFFFFFFFF : 0x7FFFFFFF;
            out.write(new byte[] {
                // payload length - always 5
                0b00000000,
                0b00000000,
                0b00000101,

                // type
                0x02,

                // unused flags
                0b00000000,

                // stream id
                (byte) (streamId >> 24),
                (byte) (streamId >> 16),
                (byte) (streamId >> 8),
                (byte) streamId,

                // exclusive bit + stream dependency id
                (byte) ((streamDependencyId >> 24) & exclusiveMask),
                (byte) (streamDependencyId >> 16),
                (byte) (streamDependencyId >> 8),
                (byte) streamDependencyId,

                // weight
                (byte)weight
            });
        }

    }
}
