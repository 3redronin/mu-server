package io.muserver;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static io.muserver.MuServerBuilder.httpsServer;
import static io.muserver.RFCTestUtils.encodeFieldBlock;
import static io.muserver.RFCTestUtils.headersFrame;
import static io.muserver.RFCTestUtils.readIgnoringWindowUpdates;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

class Http2AuthorityTest {
    private @Nullable MuServer server;

    @AfterEach void stop() {
        if (server != null) server.stop();
    }

    @ParameterizedTest
    @CsvSource({"alpha.example,alpha.example,https://alpha.example/authority",
        "service_name.example,service_name.example,https://service_name.example/authority",
        "foo~bar,foo~bar,https://foo~bar/authority",
        "Service_Name.Example,service_name.example,https://Service_Name.Example/authority",
        "Foo~Bar,foo~bar,https://Foo~Bar/authority",
        "Service_Name.Example:443,service_name.example:443,https://Service_Name.Example:443/authority",
        "Alpha.Example,alpha.example,https://Alpha.Example/authority",
        "Alpha.Example:443,alpha.example:443,https://Alpha.Example:443/authority"})
    void equivalentHostUsesAuthorityForRequestUri(String authority, String host, String expectedUri) throws Exception {
        server = server();
        try (var client = new H2Client(); var con = client.connect(server)) {
            con.handshake().writeRaw(headersFrame(1, true, true, encodeFieldBlock(headers(authority, host)))).flush();
            assertThat(readIgnoringWindowUpdates(con, Http2HeadersFrame.class).headers().get(":status"), equalTo("200"));
            assertThat(readIgnoringWindowUpdates(con, Http2DataFrame.class).toUTF8(),
                equalTo(expectedUri + "\n" + authority));
        }
    }

    @ParameterizedTest
    @CsvSource({"alpha.example,beta.example", "alpha.example:443,alpha.example:444",
        "alpha.example:443,alpha.example", "foo~bar:443,foo~bar:444",
        "foo%2Dbar,foo-bar"})
    void nonMatchingHostResetsOnlyTheStream(String authority, String host) throws Exception {
        server = server();
        try (var client = new H2Client(); var con = client.connect(server)) {
            con.handshake().writeRaw(headersFrame(1, true, true, encodeFieldBlock(headers(authority, host)))).flush();
            var reset = readIgnoringWindowUpdates(con, Http2ResetStreamFrame.class);
            assertThat(reset.streamId(), equalTo(1));
            assertThat(reset.errorCodeEnum(), equalTo(Http2ErrorCode.PROTOCOL_ERROR));

            con.writeRaw(headersFrame(3, true, true, encodeFieldBlock(headers("alpha.example", "alpha.example")))).flush();
            assertThat(readIgnoringWindowUpdates(con, Http2HeadersFrame.class).headers().get(":status"), equalTo("200"));
            assertThat(readIgnoringWindowUpdates(con, Http2DataFrame.class).toUTF8(),
                equalTo("https://alpha.example/authority\nalpha.example"));
        }
    }

    @Test
    void hostWithoutAuthoritySuppliesTheRequestUri() throws Exception {
        server = server();
        try (var client = new H2Client(); var con = client.connect(server)) {
            con.handshake().writeRaw(headersFrame(1, true, true, encodeFieldBlock(headers(null, "host.example")))).flush();
            assertThat(readIgnoringWindowUpdates(con, Http2HeadersFrame.class).headers().get(":status"), equalTo("200"));
            assertThat(readIgnoringWindowUpdates(con, Http2DataFrame.class).toUTF8(),
                equalTo("https://host.example/authority\nhost.example"));
        }
    }

    @Test
    void neitherAuthorityNorHostIsRejectedByRequestVerifier() throws Exception {
        server = server();
        try (var client = new H2Client(); var con = client.connect(server)) {
            con.handshake().writeRaw(headersFrame(1, true, true, encodeFieldBlock(headers(null, null)))).flush();
            assertThat(readIgnoringWindowUpdates(con, Http2HeadersFrame.class).headers().get(":status"), equalTo("400"));
        }
    }

    private static MuServer server() {
        return httpsServer().withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/authority", (request, response, pathParams) ->
                response.write(request.uri() + "\n" + request.headers().get(HeaderNames.HOST)))
            .start();
    }

    private static FieldBlock headers(@Nullable String authority, @Nullable String host) {
        FieldBlock headers = new FieldBlock();
        headers.add(":method", "GET");
        headers.add(":scheme", "https");
        if (authority != null) headers.add(":authority", authority);
        headers.add(":path", "/authority");
        if (host != null) headers.add("host", host);
        return headers;
    }
}
