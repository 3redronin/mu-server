package io.muserver;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.muserver.MuServerBuilder.httpsServer;
import static io.muserver.RFCTestUtils.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for RFC 9113 §8.1 HTTP Message Framing and §8.1.1 Malformed Messages.
 *
 * <p>RFC 9113 §8.1: HTTP requests and responses are exchanged over a single HTTP/2 connection.
 * A client sends an HTTP request on a new stream. A request consists of a HEADERS frame followed
 * by zero or more DATA frames.</p>
 *
 * <p>RFC 9113 §8.1.1: A request or response that is defined as malformed MUST be treated as a
 * stream error (Section 5.4.2) of type PROTOCOL_ERROR.</p>
 */
@DisplayName("RFC 9113 §8.1 HTTP Message Framing")
class RFC9113_8_1_HttpMessageFramingTest {

    private @Nullable MuServer server;

    @Test
    void requestBodyIsConveyedInDataFrames() throws Exception {
        var receivedBody = new AtomicReference<String>();
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.POST, "/hello", (request, response, pathParams) -> {
                receivedBody.set(request.readBodyAsString());
                response.status(200);
            })
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            var bodyBytes = "hello world".getBytes(StandardCharsets.UTF_8);
            con.handshake()
                .writeFrame(new Http2HeadersFrame(1, false, postHelloHeaders(getPort())))
                .writeFrame(new Http2DataFrame(1, true, bodyBytes, 0, bodyBytes.length))
                .flush();

            var response = readIgnoringWindowUpdates(con, Http2HeadersFrame.class);
            assertThat(response.headers().get(":status"), equalTo("200"));
            assertThat(receivedBody.get(), equalTo("hello world"));
        }
    }

    @Test
    void requestBodyCanSpanMultipleDataFrames() throws Exception {
        var receivedBody = new AtomicReference<String>();
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.POST, "/hello", (request, response, pathParams) -> {
                receivedBody.set(request.readBodyAsString());
                response.status(200);
            })
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            byte[] part1 = "hello ".getBytes(StandardCharsets.UTF_8);
            byte[] part2 = "world".getBytes(StandardCharsets.UTF_8);

            con.handshake()
                .writeFrame(new Http2HeadersFrame(1, false, postHelloHeaders(getPort())))
                .writeFrame(new Http2DataFrame(1, false, part1, 0, part1.length))
                .writeFrame(new Http2DataFrame(1, true, part2, 0, part2.length))
                .flush();

            var response = readIgnoringWindowUpdates(con, Http2HeadersFrame.class);
            assertThat(response.headers().get(":status"), equalTo("200"));
            assertThat(receivedBody.get(), equalTo("hello world"));
        }
    }

    @Test
    void endStreamFlagOnHeadersIndicatesNoRequestBody() throws Exception {
        var gotRequest = new CountDownLatch(1);
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> {
                gotRequest.countDown();
                response.status(204);
            })
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            // END_STREAM set on HEADERS frame — no DATA frames will follow
            con.handshake()
                .writeFrame(new Http2HeadersFrame(1, true, getHelloHeaders(getPort())))
                .flush();

            assertThat(gotRequest.await(5, TimeUnit.SECONDS), equalTo(true));
            var response = readIgnoringWindowUpdates(con, Http2HeadersFrame.class);
            assertThat(response.headers().get(":status"), equalTo("204"));
        }
    }

    @Test
    void multipleRequestsCanBeInterleavedOnSeparateStreams() throws Exception {
        var firstStarted = new CountDownLatch(1);
        var letFirstFinish = new CountDownLatch(1);
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> {
                firstStarted.countDown();
                if (letFirstFinish.await(5, TimeUnit.SECONDS)) {
                    response.status(200);
                }
            })
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            con.handshake()
                .writeFrame(new Http2HeadersFrame(1, true, getHelloHeaders(getPort())))
                .writeFrame(new Http2HeadersFrame(3, true, getHelloHeaders(getPort())))
                .flush();

            assertThat(firstStarted.await(5, TimeUnit.SECONDS), equalTo(true));
            letFirstFinish.countDown();

            // Both responses should arrive; order may vary
            var resp1 = readIgnoringWindowUpdates(con, Http2HeadersFrame.class);
            var resp3 = readIgnoringWindowUpdates(con, Http2HeadersFrame.class);
            assertThat(resp1.headers().get(":status"), equalTo("200"));
            assertThat(resp3.headers().get(":status"), equalTo("200"));
        }
    }

    @Test
    void malformedRequestReceivesStreamError() throws Exception {
        // RFC 9113 §8.1.1: A request or response that is defined as malformed MUST be treated as
        // a stream error of type PROTOCOL_ERROR.
        // Here we send a HEADERS frame on stream 1 without the required :path pseudo-header.
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> response.status(200))
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            // Omit the :path pseudo-header — malformed request
            var malformedHeaders = new FieldBlock();
            malformedHeaders.add(":scheme", "https");
            malformedHeaders.add(":authority", "localhost:" + getPort());
            malformedHeaders.add(":method", "GET");

            con.handshake()
                .writeRaw(headersFrame(1, true, true, encodeFieldBlock(malformedHeaders)))
                .flush();

            // Server should send RST_STREAM(PROTOCOL_ERROR) and keep the connection open
            var reset = readIgnoringWindowUpdates(con, Http2ResetStreamFrame.class);
            assertThat(reset.streamId(), equalTo(1));
            assertThat(reset.errorCodeEnum(), equalTo(Http2ErrorCode.PROTOCOL_ERROR));

            // Connection remains usable for subsequent well-formed requests
            con.writeFrame(new Http2HeadersFrame(3, true, getHelloHeaders(getPort())))
                .flush();
            var response = readIgnoringWindowUpdates(con, Http2HeadersFrame.class);
            assertThat(response.streamId(), equalTo(3));
            assertThat(response.headers().get(":status"), equalTo("200"));
        }
    }

    @Test
    void contentLengthMustMatchSingleDataFramePayloadLength() throws Exception {
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.POST, "/hello", (request, response, pathParams) -> {
                request.readBodyAsString();
                response.status(200);
            })
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            var headers = postHelloHeaders(getPort());
            headers.add("content-length", "5");

            con.handshake()
                .writeFrame(new Http2HeadersFrame(1, false, headers))
                .writeFrame(utf8DataFrame(1, true, "1234"))
                .flush();

            var reset = readIgnoringWindowUpdates(con, Http2ResetStreamFrame.class);
            assertThat(reset.streamId(), equalTo(1));
            assertThat(reset.errorCodeEnum(), equalTo(Http2ErrorCode.PROTOCOL_ERROR));
        }
    }

    @Test
    void contentLengthMustMatchTheSumOfMultipleDataFrames() throws Exception {
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.POST, "/hello", (request, response, pathParams) -> {
                request.readBodyAsString();
                response.status(200);
            })
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            var headers = postHelloHeaders(getPort());
            headers.add("content-length", "5");

            con.handshake()
                .writeFrame(new Http2HeadersFrame(1, false, headers))
                .writeFrame(utf8DataFrame(1, false, "12"))
                .writeFrame(utf8DataFrame(1, true, "34"))
                .flush();

            var reset = readIgnoringWindowUpdates(con, Http2ResetStreamFrame.class);
            assertThat(reset.streamId(), equalTo(1));
            assertThat(reset.errorCodeEnum(), equalTo(Http2ErrorCode.PROTOCOL_ERROR));
        }
    }

    private int getPort() {
        return server.uri().getPort();
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop();
    }
}
