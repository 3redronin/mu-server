package io.muserver;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static io.muserver.MuServerBuilder.httpsServer;
import static io.muserver.RFCTestUtils.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for RFC 9113 §8.2 HTTP Fields.
 *
 * <p>§8.2.1 Field Validity: Field names are strings of ASCII characters that are
 * compared case-insensitively. However, header field names MUST be converted to
 * lowercase prior to their encoding in HTTP/2. A request containing uppercase
 * header field names MUST be treated as malformed (§8.1.1).</p>
 *
 * <p>§8.2.2 Connection-Specific Header Fields: HTTP/2 does not use the Connection
 * header field to indicate connection-specific fields; this protocol uses other
 * mechanisms for managing connections. Any message containing connection-specific
 * header fields MUST be treated as malformed. The only exception is the TE header
 * field, which MAY appear with the value "trailers" only.</p>
 */
@DisplayName("RFC 9113 §8.2 HTTP Fields")
class RFC9113_8_2_HttpFieldsTest {

    private @Nullable MuServer server;

    // -------------------------------------------------------------------------
    // §8.2.1 Field Validity
    // -------------------------------------------------------------------------

    @Test
    void uppercaseFieldNamesAreTreatedAsMalformed() throws Exception {
        // RFC 9113 §8.2.1: A request containing uppercase header field names MUST
        // be treated as malformed (§8.1.1) → stream error PROTOCOL_ERROR.
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> response.status(200))
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            // Encode valid base headers and append an uppercase literal header manually.
            // HPACK encoding for literal without indexing with new name:
            //   0x00 = literal without indexing, name index = 0 (new name)
            //   nameLen (7-bit integer, Huffman bit = 0)
            //   name bytes
            //   valueLen
            //   value bytes
            byte[] base = encodeFieldBlock(getHelloHeaders(getPort()));
            byte[] badHeader = appendLiteralHeader(base, "X-FOO", "bar");

            con.handshake()
                .writeRaw(headersFrame(1, true, true, badHeader))
                .flush();

            var reset = readIgnoringWindowUpdates(con, Http2ResetStreamFrame.class);
            assertThat(reset.streamId(), equalTo(1));
            assertThat(reset.errorCodeEnum(), equalTo(Http2ErrorCode.PROTOCOL_ERROR));
        }
    }

    // -------------------------------------------------------------------------
    // §8.2.2 Connection-Specific Header Fields
    // -------------------------------------------------------------------------

    @Test
    void connectionHeaderIsRejected() throws Exception {
        // RFC 9113 §8.2.2: Any message containing connection-specific header fields
        // MUST be treated as malformed → stream error PROTOCOL_ERROR.
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> response.status(200))
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            var headers = getHelloHeaders(getPort());
            headers.add("connection", "keep-alive");

            con.handshake()
                .writeRaw(headersFrame(1, true, true, encodeFieldBlock(headers)))
                .flush();

            var reset = readIgnoringWindowUpdates(con, Http2ResetStreamFrame.class);
            assertThat(reset.streamId(), equalTo(1));
            assertThat(reset.errorCodeEnum(), equalTo(Http2ErrorCode.PROTOCOL_ERROR));
        }
    }

    @Test
    void keepAliveHeaderIsRejected() throws Exception {
        // RFC 9113 §8.2.2: Keep-Alive MUST NOT be used in HTTP/2.
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> response.status(200))
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            var headers = getHelloHeaders(getPort());
            headers.add("keep-alive", "timeout=5");

            con.handshake()
                .writeRaw(headersFrame(1, true, true, encodeFieldBlock(headers)))
                .flush();

            var reset = readIgnoringWindowUpdates(con, Http2ResetStreamFrame.class);
            assertThat(reset.streamId(), equalTo(1));
            assertThat(reset.errorCodeEnum(), equalTo(Http2ErrorCode.PROTOCOL_ERROR));
        }
    }

    @Test
    void proxyConnectionHeaderIsRejected() throws Exception {
        // RFC 9113 §8.2.2: Proxy-Connection MUST NOT be used in HTTP/2.
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> response.status(200))
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            var headers = getHelloHeaders(getPort());
            headers.add("proxy-connection", "keep-alive");

            con.handshake()
                .writeRaw(headersFrame(1, true, true, encodeFieldBlock(headers)))
                .flush();

            var reset = readIgnoringWindowUpdates(con, Http2ResetStreamFrame.class);
            assertThat(reset.streamId(), equalTo(1));
            assertThat(reset.errorCodeEnum(), equalTo(Http2ErrorCode.PROTOCOL_ERROR));
        }
    }

    @Test
    void upgradeHeaderIsRejected() throws Exception {
        // RFC 9113 §8.2.2: Upgrade MUST NOT be used in HTTP/2.
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> response.status(200))
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            var headers = getHelloHeaders(getPort());
            headers.add("upgrade", "websocket");

            con.handshake()
                .writeRaw(headersFrame(1, true, true, encodeFieldBlock(headers)))
                .flush();

            var reset = readIgnoringWindowUpdates(con, Http2ResetStreamFrame.class);
            assertThat(reset.streamId(), equalTo(1));
            assertThat(reset.errorCodeEnum(), equalTo(Http2ErrorCode.PROTOCOL_ERROR));
        }
    }

    @Test
    void transferEncodingHeaderIsRejected() throws Exception {
        // RFC 9113 §8.2.2: Transfer-Encoding MUST NOT be used in HTTP/2.
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> response.status(200))
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            var headers = getHelloHeaders(getPort());
            headers.add("transfer-encoding", "chunked");

            con.handshake()
                .writeRaw(headersFrame(1, true, true, encodeFieldBlock(headers)))
                .flush();

            var reset = readIgnoringWindowUpdates(con, Http2ResetStreamFrame.class);
            assertThat(reset.streamId(), equalTo(1));
            assertThat(reset.errorCodeEnum(), equalTo(Http2ErrorCode.PROTOCOL_ERROR));
        }
    }

    @Test
    void teHeaderWithTrailersValueIsPermitted() throws Exception {
        // RFC 9113 §8.2.2: The TE header field MAY appear in an HTTP/2 request; when it is,
        // it MUST NOT contain any value other than "trailers".
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> response.status(200))
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            var headers = getHelloHeaders(getPort());
            headers.add("te", "trailers");

            con.handshake()
                .writeRaw(headersFrame(1, true, true, encodeFieldBlock(headers)))
                .flush();

            // TE: trailers is explicitly permitted — must succeed
            var response = readIgnoringWindowUpdates(con, Http2HeadersFrame.class);
            assertThat(response.streamId(), equalTo(1));
            assertThat(response.headers().get(":status"), equalTo("200"));
        }
    }

    @Test
    void teHeaderWithNonTrailersValueIsRejected() throws Exception {
        // RFC 9113 §8.2.2: A TE header field with any value other than "trailers" is malformed.
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> response.status(200))
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            var headers = getHelloHeaders(getPort());
            headers.add("te", "gzip");

            con.handshake()
                .writeRaw(headersFrame(1, true, true, encodeFieldBlock(headers)))
                .flush();

            var reset = readIgnoringWindowUpdates(con, Http2ResetStreamFrame.class);
            assertThat(reset.streamId(), equalTo(1));
            assertThat(reset.errorCodeEnum(), equalTo(Http2ErrorCode.PROTOCOL_ERROR));
        }
    }

    @Test
    void connectionStaysOpenAfterSingleStreamError() throws Exception {
        // Stream errors (RST_STREAM) MUST NOT tear down the connection.
        // After a malformed request on stream 1, stream 3 should work normally.
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> response.status(200))
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            // Send malformed request with connection header on stream 1
            var badHeaders = getHelloHeaders(getPort());
            badHeaders.add("connection", "keep-alive");

            con.handshake()
                .writeRaw(headersFrame(1, true, true, encodeFieldBlock(badHeaders)))
                .flush();

            var reset = readIgnoringWindowUpdates(con, Http2ResetStreamFrame.class);
            assertThat(reset.streamId(), equalTo(1));
            assertThat(reset.errorCodeEnum(), equalTo(Http2ErrorCode.PROTOCOL_ERROR));

            // Connection still usable on new stream
            con.writeFrame(new Http2HeadersFrame(3, true, getHelloHeaders(getPort())))
                .flush();

            var response = readIgnoringWindowUpdates(con, Http2HeadersFrame.class);
            assertThat(response.streamId(), equalTo(3));
            assertThat(response.headers().get(":status"), equalTo("200"));
        }
    }

    // -------------------------------------------------------------------------
    // Helper: append a raw literal HPACK header (without indexing, new name).
    // Used to inject non-standard bytes such as uppercase header names that the
    // standard encoder would otherwise lowercase.
    // -------------------------------------------------------------------------

    /**
     * Appends a literal-without-indexing HPACK entry with a new name to an existing
     * encoded field block fragment.  Both name and value are written without Huffman
     * encoding.  This allows injecting header names that the normal encoder would
     * reject or transform (e.g. uppercase names).
     */
    static byte[] appendLiteralHeader(byte[] base, String name, String value) {
        byte[] nameBytes = name.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] valueBytes = value.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        // 1 byte type (0x00) + 1 byte name length + name + 1 byte value length + value
        byte[] extra = new byte[1 + 1 + nameBytes.length + 1 + valueBytes.length];
        extra[0] = 0x00; // literal without indexing, new name
        extra[1] = (byte) nameBytes.length; // name length, Huffman bit = 0
        System.arraycopy(nameBytes, 0, extra, 2, nameBytes.length);
        extra[2 + nameBytes.length] = (byte) valueBytes.length;
        System.arraycopy(valueBytes, 0, extra, 3 + nameBytes.length, valueBytes.length);

        byte[] result = new byte[base.length + extra.length];
        System.arraycopy(base, 0, result, 0, base.length);
        System.arraycopy(extra, 0, result, base.length, extra.length);
        return result;
    }

    private int getPort() {
        return server.uri().getPort();
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop();
    }
}
