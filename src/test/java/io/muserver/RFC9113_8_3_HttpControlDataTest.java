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
 * Tests for RFC 9113 §8.3 HTTP Control Data.
 *
 * <p>§8.3.1 Request Pseudo-Header Fields: All HTTP/2 requests MUST include exactly
 * one valid value for the :method, :scheme, and :path pseudo-header fields, unless
 * it is a CONNECT request. A request that omits any of these pseudo-header fields,
 * or that provides an empty :path, is malformed.</p>
 *
 * <p>§8.3.2 Response Pseudo-Header Fields: HTTP/2 responses MUST include a :status
 * pseudo-header field.</p>
 *
 * <p>Pseudo-header fields MUST appear before any regular header field in a field
 * block. A request or response that contains a pseudo-header field that appears in
 * a position after a regular header field is malformed.</p>
 *
 * <p>Pseudo-header fields defined for requests MUST NOT appear in responses.
 * Pseudo-header fields defined for responses MUST NOT appear in requests.
 * Endpoints MUST treat a request or response that contains undefined or invalid
 * pseudo-header fields as malformed.</p>
 */
@DisplayName("RFC 9113 §8.3 HTTP Control Data")
class RFC9113_8_3_HttpControlDataTest {

    private @Nullable MuServer server;

    // -------------------------------------------------------------------------
    // §8.3.1 Request Pseudo-Header Fields
    // -------------------------------------------------------------------------

    @Test
    void requestsMustIncludeMethodPseudoHeader() throws Exception {
        // RFC 9113 §8.3.1: A request without :method is malformed → stream error PROTOCOL_ERROR.
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> response.status(200))
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            var headers = new FieldBlock();
            headers.add(":scheme", "https");
            headers.add(":authority", "localhost:" + getPort());
            headers.add(":path", "/hello");

            con.handshake()
                .writeRaw(headersFrame(1, true, true, encodeFieldBlock(headers)))
                .flush();

            var reset = readIgnoringWindowUpdates(con, Http2ResetStreamFrame.class);
            assertThat(reset.streamId(), equalTo(1));
            assertThat(reset.errorCodeEnum(), equalTo(Http2ErrorCode.PROTOCOL_ERROR));
        }
    }

    @Test
    void requestsMustIncludePathPseudoHeader() throws Exception {
        // RFC 9113 §8.3.1: A request without :path is malformed → stream error PROTOCOL_ERROR.
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> response.status(200))
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            var headers = new FieldBlock();
            headers.add(":scheme", "https");
            headers.add(":authority", "localhost:" + getPort());
            headers.add(":method", "GET");

            con.handshake()
                .writeRaw(headersFrame(1, true, true, encodeFieldBlock(headers)))
                .flush();

            var reset = readIgnoringWindowUpdates(con, Http2ResetStreamFrame.class);
            assertThat(reset.streamId(), equalTo(1));
            assertThat(reset.errorCodeEnum(), equalTo(Http2ErrorCode.PROTOCOL_ERROR));
        }
    }

    @Test
    void requestsMustIncludeSchemePseudoHeader() throws Exception {
        // RFC 9113 §8.3.1: A request without :scheme is malformed → stream error PROTOCOL_ERROR.
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> response.status(200))
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            var headers = new FieldBlock();
            headers.add(":authority", "localhost:" + getPort());
            headers.add(":method", "GET");
            headers.add(":path", "/hello");

            con.handshake()
                .writeRaw(headersFrame(1, true, true, encodeFieldBlock(headers)))
                .flush();

            var reset = readIgnoringWindowUpdates(con, Http2ResetStreamFrame.class);
            assertThat(reset.streamId(), equalTo(1));
            assertThat(reset.errorCodeEnum(), equalTo(Http2ErrorCode.PROTOCOL_ERROR));
        }
    }

    @Test
    void pathPseudoHeaderMustNotBeEmpty() throws Exception {
        // RFC 9113 §8.3.1: The :path pseudo-header MUST NOT be empty for any request.
        // An empty :path is malformed → stream error PROTOCOL_ERROR.
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> response.status(200))
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            // Encode :path="" manually in HPACK because the standard encoder
            // would prevent encoding an empty :path value via FieldBlock.
            // The HPACK encoding for an indexed name (:path = static table index 4)
            // with a literal value "":
            //   0x44  = literal without indexing, name index = 4 (:path)
            //   0x00  = empty value (length 0, Huffman bit = 0)
            byte[] base = encodeFieldBlock(getHelloHeaders(getPort()));
            // Strip the original :path value and use a raw HPACK block instead.
            byte[] rawBlock = buildRequestWithEmptyPath(getPort());

            con.handshake()
                .writeRaw(headersFrame(1, true, true, rawBlock))
                .flush();

            var reset = readIgnoringWindowUpdates(con, Http2ResetStreamFrame.class);
            assertThat(reset.streamId(), equalTo(1));
            assertThat(reset.errorCodeEnum(), equalTo(Http2ErrorCode.PROTOCOL_ERROR));
        }
    }

    @Test
    void unknownPseudoHeadersAreMalformed() throws Exception {
        // RFC 9113 §8.3: Endpoints MUST treat a request that contains undefined
        // pseudo-header fields as malformed → stream error PROTOCOL_ERROR.
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> response.status(200))
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            // Encode headers with an unknown pseudo-header :extension
            byte[] base = encodeFieldBlock(getHelloHeaders(getPort()));
            // Append a raw literal for ":extension: value" (new name, no indexing)
            byte[] badBlock = RFC9113_8_2_HttpFieldsTest.appendLiteralHeader(base, ":extension", "value");

            con.handshake()
                .writeRaw(headersFrame(1, true, true, badBlock))
                .flush();

            var reset = readIgnoringWindowUpdates(con, Http2ResetStreamFrame.class);
            assertThat(reset.streamId(), equalTo(1));
            assertThat(reset.errorCodeEnum(), equalTo(Http2ErrorCode.PROTOCOL_ERROR));
        }
    }

    @Test
    void pseudoHeadersMustAppearBeforeRegularHeaders() throws Exception {
        // RFC 9113 §8.3: A request containing a pseudo-header field that appears
        // in a position after a regular header field is malformed → PROTOCOL_ERROR.
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> response.status(200))
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            // Build a field block with regular header before pseudo-header
            var headers = new FieldBlock();
            headers.add(":scheme", "https");
            headers.add(":authority", "localhost:" + getPort());
            headers.add("x-custom", "value");   // regular header first
            headers.add(":method", "GET");       // pseudo-header after regular
            headers.add(":path", "/hello");

            con.handshake()
                .writeRaw(headersFrame(1, true, true, encodeFieldBlock(headers)))
                .flush();

            var reset = readIgnoringWindowUpdates(con, Http2ResetStreamFrame.class);
            assertThat(reset.streamId(), equalTo(1));
            assertThat(reset.errorCodeEnum(), equalTo(Http2ErrorCode.PROTOCOL_ERROR));
        }
    }

    @Test
    void duplicateMethodPseudoHeaderIsMalformed() throws Exception {
        // RFC 9113 §8.3.1: A request with a duplicate :method pseudo-header is malformed.
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> response.status(200))
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            var headers = new FieldBlock();
            headers.add(":method", "GET");
            headers.add(":method", "POST");
            headers.add(":scheme", "https");
            headers.add(":authority", "localhost:" + getPort());
            headers.add(":path", "/hello");

            con.handshake()
                .writeRaw(headersFrame(1, true, true, encodeFieldBlock(headers)))
                .flush();

            var reset = readIgnoringWindowUpdates(con, Http2ResetStreamFrame.class);
            assertThat(reset.streamId(), equalTo(1));
            assertThat(reset.errorCodeEnum(), equalTo(Http2ErrorCode.PROTOCOL_ERROR));
        }
    }

    @Test
    void duplicatePathPseudoHeaderIsMalformed() throws Exception {
        // RFC 9113 §8.3.1: A request with a duplicate :path pseudo-header is malformed.
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> response.status(200))
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            var headers = new FieldBlock();
            headers.add(":method", "GET");
            headers.add(":scheme", "https");
            headers.add(":authority", "localhost:" + getPort());
            headers.add(":path", "/hello");
            headers.add(":path", "/world");

            con.handshake()
                .writeRaw(headersFrame(1, true, true, encodeFieldBlock(headers)))
                .flush();

            var reset = readIgnoringWindowUpdates(con, Http2ResetStreamFrame.class);
            assertThat(reset.streamId(), equalTo(1));
            assertThat(reset.errorCodeEnum(), equalTo(Http2ErrorCode.PROTOCOL_ERROR));
        }
    }

    // -------------------------------------------------------------------------
    // §8.3.2 Response Pseudo-Header Fields
    // -------------------------------------------------------------------------

    @Test
    void responsesAlwaysIncludeStatusPseudoHeader() throws Exception {
        // RFC 9113 §8.3.2: All HTTP/2 responses MUST include a :status pseudo-header field.
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> response.status(201))
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            con.handshake()
                .writeFrame(new Http2HeadersFrame(1, true, getHelloHeaders(getPort())))
                .flush();

            var response = readIgnoringWindowUpdates(con, Http2HeadersFrame.class);
            assertThat(response.streamId(), equalTo(1));
            // :status MUST be present and MUST be the first pseudo-header
            assertThat(response.headers().get(":status"), equalTo("201"));
        }
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    /**
     * Builds a raw HPACK field block for a GET /hello request where :path is
     * present but has an empty value.  This cannot be expressed through the normal
     * FieldBlock API because the encoder would reject an empty path.
     *
     * <p>The encoding uses:
     * <ul>
     *   <li>0x82 – indexed :method GET (static table index 2)</li>
     *   <li>0x87 – indexed :scheme https (static table index 7)</li>
     *   <li>0x44 – literal without indexing, name index 4 (:path), then empty value</li>
     *   <li>Literal :authority header with the given port</li>
     * </ul>
     */
    private byte[] buildRequestWithEmptyPath(int port) throws java.io.IOException {
        var out = new java.io.ByteArrayOutputStream();
        // :method: GET (static index 2) → indexed representation 0x82
        out.write(0x82);
        // :scheme: https (static index 7) → indexed representation 0x87
        out.write(0x87);
        // :path: "" (static index 4 for :path name, empty literal value)
        // Literal without indexing, indexed name 4: 0b0000_0100 = 0x04
        out.write(0x04);
        // Value: empty string, length = 0, no Huffman
        out.write(0x00);
        // :authority: localhost:<port>  (static index 1 for :authority name)
        // Literal without indexing, indexed name 1: 0b0000_0001 = 0x01
        out.write(0x01);
        var authority = ("localhost:" + port).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        out.write(authority.length); // length, no Huffman
        out.write(authority);
        return out.toByteArray();
    }

    private int getPort() {
        return server.uri().getPort();
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop();
    }
}
