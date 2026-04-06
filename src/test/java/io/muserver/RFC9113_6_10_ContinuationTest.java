package io.muserver;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.Arrays;
import static io.muserver.MuServerBuilder.httpsServer;
import static io.muserver.RFCTestUtils.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;
@DisplayName("RFC 9113 6.10 Frame Definitions: CONTINUATION")
class RFC9113_6_10_ContinuationTest {
    private @Nullable MuServer server;
    @Test
    void headerBlocksCanSpanContinuationFrames() throws Exception {
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> response.status(202))
            .start();
        try (var client = new H2Client();
             var con = client.connect(server)) {
            byte[] encoded = encodeFieldBlock(getHelloHeaders(getPort()));
            byte[] first = Arrays.copyOfRange(encoded, 0, 5);
            byte[] second = Arrays.copyOfRange(encoded, 5, encoded.length);
            con.handshake()
                .writeRaw(headersFrame(1, true, false, first))
                .writeRaw(continuationFrame(1, true, second))
                .flush();
            var response = con.readLogicalFrame(Http2HeadersFrame.class);
            assertThat(response.streamId(), equalTo(1));
            assertThat(response.headers().get(":status"), equalTo("202"));
        }
    }
    @Test
    void strayContinuationFramesAreConnectionErrors() throws Exception {
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .start();
        try (var client = new H2Client();
             var con = client.connect(server)) {
            con.handshake()
                .writeRaw(continuationFrame(1, true, new byte[] {1, 2, 3}))
                .flush();
            assertThat(con.readLogicalFrame(), equalTo(goAway(0, Http2ErrorCode.PROTOCOL_ERROR)));
            assertThrows(IOException.class, con::readFrameHeader);
        }
    }
    @Test
    void continuationFramesMustStayOnTheSameStream() throws Exception {
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .start();
        try (var client = new H2Client();
             var con = client.connect(server)) {
            byte[] encoded = encodeFieldBlock(getHelloHeaders(getPort()));
            byte[] first = Arrays.copyOfRange(encoded, 0, 5);
            byte[] second = Arrays.copyOfRange(encoded, 5, encoded.length);
            con.handshake()
                .writeRaw(headersFrame(1, true, false, first))
                .writeRaw(continuationFrame(3, true, second))
                .flush();
            assertThat(con.readLogicalFrame(), equalTo(goAway(0, Http2ErrorCode.PROTOCOL_ERROR)));
            assertThrows(IOException.class, con::readFrameHeader);
        }
    }
    private int getPort() {
        return server.uri().getPort();
    }
    @AfterEach
    public void stop() {
        if (server != null) server.stop();
    }
}
