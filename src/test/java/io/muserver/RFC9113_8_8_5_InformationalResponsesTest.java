package io.muserver;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static io.muserver.MuServerBuilder.httpsServer;
import static io.muserver.RFCTestUtils.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.nullValue;

/**
 * Tests for RFC 9113 §8.8.5 Informational Responses.
 */
@DisplayName("RFC 9113 §8.8.5 Informational Responses")
class RFC9113_8_8_5_InformationalResponsesTest {

    private @Nullable MuServer server;

    @Test
    void expectContinueIsSentBeforeRequestBodyArrives() throws Exception {
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.POST, "/hello", (request, response, pathParams) -> response.write(request.readBodyAsString()))
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            FieldBlock headers = postHelloHeaders(getPort());
            headers.add("expect", "100-continue");
            headers.add("content-length", "11");

            con.handshake()
                .writeFrame(new Http2HeadersFrame(1, false, headers))
                .flush();

            var informational = readIgnoringWindowUpdates(con, Http2HeadersFrame.class);
            assertThat(informational.streamId(), equalTo(1));
            assertThat(informational.headers().get(":status"), equalTo("100"));

            con.writeFrame(utf8DataFrame(1, true, "hello world"))
                .flush();

            var responseHeaders = readIgnoringWindowUpdates(con, Http2HeadersFrame.class);
            assertThat(responseHeaders.streamId(), equalTo(1));
            assertThat(responseHeaders.headers().get(":status"), equalTo("200"));

            var responseBody = readIgnoringWindowUpdates(con, Http2DataFrame.class);
            assertThat(responseBody.streamId(), equalTo(1));
            assertThat(responseBody.toUTF8(), equalTo("hello world"));
        }
    }

    @Test
    void multipleInformationalResponsesCanBeSentBeforeFinalResponse() throws Exception {
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> {
                Headers earlyHints = Headers.create();
                earlyHints.add("link", "</style.css>; rel=preload; as=style");
                response.sendInformationalResponse(HttpStatus.EARLY_HINTS_103, earlyHints);

                Headers processing = Headers.create();
                processing.add("x-progress", "almost-done");
                response.sendInformationalResponse(HttpStatus.PROCESSING_102, processing);

                response.status(HttpStatus.NO_CONTENT_204);
            })
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            con.handshake()
                .writeFrame(new Http2HeadersFrame(1, true, getHelloHeaders(getPort())))
                .flush();

            var earlyHints = readIgnoringWindowUpdates(con, Http2HeadersFrame.class);
            assertThat(earlyHints.streamId(), equalTo(1));
            assertThat(earlyHints.headers().get(":status"), equalTo("103"));
            assertThat(earlyHints.headers().get("link"), equalTo("</style.css>; rel=preload; as=style"));

            var processing = readIgnoringWindowUpdates(con, Http2HeadersFrame.class);
            assertThat(processing.streamId(), equalTo(1));
            assertThat(processing.headers().get(":status"), equalTo("102"));
            assertThat(processing.headers().get("x-progress"), equalTo("almost-done"));

            var finalResponse = readIgnoringWindowUpdates(con, Http2HeadersFrame.class);
            assertThat(finalResponse.streamId(), equalTo(1));
            assertThat(finalResponse.headers().get(":status"), equalTo("204"));
            assertThat(finalResponse.headers().get("link"), nullValue());
            assertThat(finalResponse.headers().get("x-progress"), nullValue());
        }
    }

    @Test
    void informationalStatusMustBe1xx() throws Exception {
        var failure = new AtomicReference<Throwable>();
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> {
                try {
                    response.sendInformationalResponse(HttpStatus.OK_200, null);
                } catch (Throwable e) {
                    failure.set(e);
                }
                response.status(HttpStatus.NO_CONTENT_204);
            })
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            con.handshake()
                .writeFrame(new Http2HeadersFrame(1, true, getHelloHeaders(getPort())))
                .flush();

            var response = readIgnoringWindowUpdates(con, Http2HeadersFrame.class);
            assertThat(response.headers().get(":status"), equalTo("204"));
            assertThat(failure.get(), instanceOf(IllegalArgumentException.class));
        }
    }

    @Test
    void informationalResponseCannotBeSentAfterFinalHeadersHaveStarted() throws Exception {
        var failure = new AtomicReference<Throwable>();
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> {
                try (var out = response.outputStream()) {
                    try {
                        response.sendInformationalResponse(HttpStatus.EARLY_HINTS_103, null);
                    } catch (Throwable e) {
                        failure.set(e);
                    }
                    out.write("done".getBytes(StandardCharsets.UTF_8));
                }
            })
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {

            con.handshake()
                .writeFrame(new Http2HeadersFrame(1, true, getHelloHeaders(getPort())))
                .flush();

            var responseHeaders = readIgnoringWindowUpdates(con, Http2HeadersFrame.class);
            assertThat(responseHeaders.streamId(), equalTo(1));
            assertThat(responseHeaders.headers().get(":status"), equalTo("200"));

            var responseBody = readIgnoringWindowUpdates(con, Http2DataFrame.class);
            assertThat(responseBody.toUTF8(), equalTo("done"));
            assertThat(failure.get(), instanceOf(IllegalStateException.class));
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
