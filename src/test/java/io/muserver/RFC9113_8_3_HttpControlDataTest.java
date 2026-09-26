package io.muserver;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static io.muserver.FieldConformanceFixtures.*;
import static io.muserver.MuServerBuilder.httpsServer;
import static io.muserver.RFCTestUtils.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

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

            assertInitialRejection(untilReset(con, 1), 1);
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

            assertInitialRejection(untilReset(con, 1), 1);
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

            assertInitialRejection(untilReset(con, 1), 1);
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

            byte[] rawBlock = buildRequestWithEmptyPath(getPort());

            con.handshake()
                .writeRaw(headersFrame(1, true, true, rawBlock))
                .flush();

            assertInitialRejection(untilReset(con, 1), 1);
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

            assertInitialRejection(untilReset(con, 1), 1);
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

            assertInitialRejection(untilReset(con, 1), 1);
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

            assertInitialRejection(untilReset(con, 1), 1);
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

            assertInitialRejection(untilReset(con, 1), 1);
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

    private byte[] buildRequestWithEmptyPath(int port) throws IOException {
        return concat(indexed(2), indexed(7), named(4, "", false, false),
            named(1, "localhost:" + port, false, false));
    }

    private int getPort() {
        return server.uri().getPort();
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop();
    }
}
