package io.muserver;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.muserver.MuServerBuilder.httpsServer;
import static io.muserver.RFCTestUtils.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

@DisplayName("RFC 9113 6.2 Frame Definitions: HEADERS")
class RFC9113_6_2_HeadersTest {

    private @Nullable MuServer server;

    @Test
    void paddedHeadersFramesCanStartARequest() throws Exception {
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> response.status(202))
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            con.handshake()
                .writeRaw(paddedHeadersFrame(1, true, true, encodeFieldBlock(getHelloHeaders(getPort())), 2))
                .flush();

            var response = con.readLogicalFrame(Http2HeadersFrame.class);
            assertThat(response.streamId(), equalTo(1));
            assertThat(response.headers().get(":status"), equalTo("202"));
        }
    }

    @Test
    void priorityFlagOnHeadersFramesIsIgnored() throws Exception {
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> response.status(202))
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            con.handshake()
                .writeRaw(priorityHeadersFrame(1, true, true, encodeFieldBlock(getHelloHeaders(getPort())), true, 0, 10))
                .flush();

            var response = con.readLogicalFrame(Http2HeadersFrame.class);
            assertThat(response.streamId(), equalTo(1));
            assertThat(response.headers().get(":status"), equalTo("202"));
        }
    }

    @Test
    void unknownRequestPseudoHeadersAreStreamErrors() throws Exception {
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> response.status(202))
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            FieldBlock invalid = FieldBlock.newWithDate();
            invalid.add(":scheme", "https");
            invalid.add(":authority", "localhost:" + getPort());
            invalid.add(":method", "GET");
            invalid.add(":path", "/hello");
            invalid.add(":status", "200");

            con.handshake()
                .writeFrame(new Http2HeadersFrame(1, true, invalid))
                .flush();

            var reset = con.readLogicalFrame(Http2ResetStreamFrame.class);
            assertThat(reset.streamId(), equalTo(1));
            assertThat(reset.errorCodeEnum(), equalTo(Http2ErrorCode.PROTOCOL_ERROR));

            con.writeFrame(new Http2HeadersFrame(3, true, getHelloHeaders(getPort()))).flush();
            var response = con.readLogicalFrame(Http2HeadersFrame.class);
            assertThat(response.streamId(), equalTo(3));
            assertThat(response.headers().get(":status"), equalTo("202"));
        }
    }

    @Test
    void requestPseudoHeadersMustComeBeforeRegularHeaders() throws Exception {
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> response.status(202))
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            FieldBlock invalid = new FieldBlock();
            invalid.add(":scheme", "https");
            invalid.add(":authority", "localhost:" + getPort());
            invalid.add(":method", "GET");
            invalid.add("accept", "*/*");
            invalid.add(":path", "/hello");

            con.handshake()
                .writeFrame(new Http2HeadersFrame(1, true, invalid))
                .flush();

            var reset = con.readLogicalFrame(Http2ResetStreamFrame.class);
            assertThat(reset.streamId(), equalTo(1));
            assertThat(reset.errorCodeEnum(), equalTo(Http2ErrorCode.PROTOCOL_ERROR));

            con.writeFrame(new Http2HeadersFrame(3, true, getHelloHeaders(getPort()))).flush();
            var response = con.readLogicalFrame(Http2HeadersFrame.class);
            assertThat(response.streamId(), equalTo(3));
            assertThat(response.headers().get(":status"), equalTo("202"));
        }
    }

    @Test
    void trailingHeadersCanBeReadAfterTheRequestBody() throws Exception {
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.POST, "/hello", (request, response, pathParams) -> {
                response.write(request.readBodyAsString() + "|" + request.trailers().get("checksum"));
            })
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            FieldBlock trailers = new FieldBlock();
            trailers.add("checksum", "abc123");

            con.handshake()
                .writeFrame(new Http2HeadersFrame(1, false, postHelloHeaders(getPort())))
                .writeFrame(utf8DataFrame(1, false, "Hello"))
                .writeFrame(new Http2HeadersFrame(1, true, trailers))
                .flush();

            var headers = readIgnoringWindowUpdates(con, Http2HeadersFrame.class);
            assertThat(headers.streamId(), equalTo(1));
            assertThat(headers.headers().get(":status"), equalTo("200"));

            assertThat(readIgnoringWindowUpdates(con, Http2DataFrame.class).toUTF8(), equalTo("Hello|abc123"));
            assertThat(readIgnoringWindowUpdates(con, Http2DataFrame.class).endStream(), equalTo(true));
        }
    }

    @Test
    void invalidTrailerFieldsAreStreamErrors() throws Exception {
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.POST, "/hello", (request, response, pathParams) -> response.write(request.readBodyAsString()))
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            FieldBlock trailers = new FieldBlock();
            trailers.add("content-length", "123");

            con.handshake()
                .writeFrame(new Http2HeadersFrame(1, false, postHelloHeaders(getPort())))
                .writeFrame(utf8DataFrame(1, false, "Hello"))
                .writeFrame(new Http2HeadersFrame(1, true, trailers))
                .flush();

            var reset = con.readLogicalFrame(Http2ResetStreamFrame.class);
            assertThat(reset.streamId(), equalTo(1));
            assertThat(reset.errorCodeEnum(), equalTo(Http2ErrorCode.PROTOCOL_ERROR));

            con.writeFrame(new Http2HeadersFrame(3, true, postHelloHeaders(getPort()))).flush();
            var response = con.readLogicalFrame(Http2HeadersFrame.class);
            assertThat(response.streamId(), equalTo(3));
            assertThat(response.headers().get(":status"), equalTo("200"));
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




