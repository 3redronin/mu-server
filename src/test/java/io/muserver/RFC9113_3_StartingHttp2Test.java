package io.muserver;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import scaffolding.Http1Client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static io.muserver.MuServerBuilder.httpServer;
import static io.muserver.MuServerBuilder.httpsServer;
import static io.muserver.RFCTestUtils.goAway;
import static io.muserver.RFCTestUtils.getHelloHeaders;
import static io.muserver.RFCTestUtils.readIgnoringWindowUpdates;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("RFC 9113 3. Starting HTTP/2")
class RFC9113_3_StartingHttp2Test {

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

            assertThat(con.readLogicalFrame(), equalTo(goAway(0, Http2ErrorCode.NO_ERROR)));
        }

    }

    @Test
    public void cleartextPriorKnowledgeCanStartHttp2() throws Exception {
        server = httpServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> response.write("hello"))
            .start();
        var httpUri = Objects.requireNonNull(server.httpUri());

        try (var client = new H2Client();
             var con = client.connectClearText(server)) {

            con.handshake()
                .writeFrame(new Http2HeadersFrame(1, true, getHelloHeaders("http", httpUri.getPort())))
                .flush();

            var headers = readIgnoringWindowUpdates(con, Http2HeadersFrame.class);
            assertThat(headers.streamId(), equalTo(1));
            assertThat(headers.headers().get(":status"), equalTo("200"));

            var data = readIgnoringWindowUpdates(con, Http2DataFrame.class);
            assertThat(data.streamId(), equalTo(1));
            assertThat(data.toUTF8(), equalTo("hello"));

            var eos = readIgnoringWindowUpdates(con, Http2DataFrame.class);
            assertThat(eos.streamId(), equalTo(1));
            assertThat(eos.endStream(), equalTo(true));
        }
    }

    @Test
    public void cleartextPriorKnowledgeCanUseAFragmentedPreface() throws Exception {
        server = httpServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .start();

        try (var client = new H2Client();
             var con = client.connectClearText(server)) {

            con.writeRaw("PRI * HTTP/2.0\r\n".getBytes(StandardCharsets.US_ASCII))
                .flush();
            con.writeRaw("\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII))
                .writeFrame(Http2Settings.DEFAULT_CLIENT_SETTINGS)
                .flush();

            assertThat(con.readLogicalFrame(), equalTo(new Http2Settings(false, 4096, 200, 65535, 16384, 8192)));
            assertThat(con.readLogicalFrame(), equalTo(Http2Settings.ACK));

            con.writeFrame(Http2Settings.ACK);
            con.writeFrame(goAway(0, Http2ErrorCode.NO_ERROR));
            con.flush();

            assertThat(con.readLogicalFrame(), equalTo(goAway(0, Http2ErrorCode.NO_ERROR)));
        }
    }

    @Test
    public void http1StillWorksWhenCleartextPriorKnowledgeIsEnabled() throws Exception {
        server = httpServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> response.write("hello"))
            .start();
        var httpUri = Objects.requireNonNull(server.httpUri());

        try (var client = Http1Client.connect(httpUri)) {
            client.writeRequestLine(Method.GET, "/hello")
                .writeHeader("connection", "close")
                .flushHeaders();

            assertThat(client.readLine(), equalTo("HTTP/1.1 200 OK"));
            var headers = client.readHeaders();
            assertThat(client.readBody(headers), equalTo("hello"));
        }
    }

    @AfterEach
    public void stop() {
        if (server != null) server.stop();
    }

}
