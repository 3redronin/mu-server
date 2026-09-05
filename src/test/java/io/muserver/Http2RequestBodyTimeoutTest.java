package io.muserver;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.muserver.MuServerBuilder.httpsServer;
import static io.muserver.RFCTestUtils.assertNothingToRead;
import static io.muserver.RFCTestUtils.readIgnoringWindowUpdates;
import static io.muserver.RFCTestUtils.utf8DataFrame;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

class Http2RequestBodyTimeoutTest {

    private @Nullable MuServer server;

    @Test
    void zeroRequestTimeoutDisablesTheHttp2BodyDeadline() throws Exception {
        var handlerStarted = new CountDownLatch(1);
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .withRequestTimeout(0, TimeUnit.MILLISECONDS)
            .addHandler(Method.POST, "/hello", (request, response, pathParams) -> {
                handlerStarted.countDown();
                response.write(request.readBodyAsString());
            })
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {
            con.handshake()
                .writeFrame(new Http2HeadersFrame(
                    1,
                    false,
                    requestHeaders("POST", "/hello", server.uri().getPort())
                ))
                .flush();

            assertThat(handlerStarted.await(5, TimeUnit.SECONDS), equalTo(true));
            assertNothingToRead(con.socket());

            con.writeFrame(utf8DataFrame(1, true, "hello"))
                .flush();

            var responseHeaders =
                readIgnoringWindowUpdates(con, Http2HeadersFrame.class);
            assertThat(responseHeaders.headers().get(":status"), equalTo("200"));
            assertThat(
                readIgnoringWindowUpdates(con, Http2DataFrame.class).toUTF8(),
                equalTo("hello")
            );
            assertThat(
                readIgnoringWindowUpdates(con, Http2DataFrame.class),
                equalTo(Http2DataFrame.eos(1))
            );
        }
    }

    @Test
    void expiredRequestTimeoutReturns408WithoutClosingTheHttp2Connection()
        throws Exception {
        var handlerStarted = new CountDownLatch(1);
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .withRequestTimeout(50, TimeUnit.MILLISECONDS)
            .addHandler(Method.POST, "/slow", (request, response, pathParams) -> {
                handlerStarted.countDown();
                request.readBodyAsString();
            })
            .addHandler(Method.GET, "/after", (request, response, pathParams) -> {
                response.status(204);
            })
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {
            con.handshake()
                .writeFrame(new Http2HeadersFrame(
                    1,
                    false,
                    requestHeaders("POST", "/slow", server.uri().getPort())
                ))
                .flush();

            assertThat(handlerStarted.await(5, TimeUnit.SECONDS), equalTo(true));
            var timeoutHeaders =
                readIgnoringWindowUpdates(con, Http2HeadersFrame.class);
            assertThat(timeoutHeaders.headers().get(":status"), equalTo("408"));
            assertThat(timeoutHeaders.headers().get("connection"), nullValue());
            drainResponse(con, timeoutHeaders);

            con.writeFrame(new Http2HeadersFrame(
                    3,
                    true,
                    requestHeaders("GET", "/after", server.uri().getPort())
                ))
                .flush();

            var afterResponse =
                readIgnoringWindowUpdates(con, Http2HeadersFrame.class);
            assertThat(afterResponse.streamId(), equalTo(3));
            assertThat(afterResponse.headers().get(":status"), equalTo("204"));

            con.writeFrame(Http2DataFrame.eos(1)).flush();
        }
    }

    @Test
    void expiredRequestTimeoutResetsOnlyAStreamWhoseResponseHasStarted()
        throws Exception {
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .withRequestTimeout(50, TimeUnit.MILLISECONDS)
            .addHandler(Method.POST, "/slow", (request, response, pathParams) -> {
                response.sendChunk("partial");
                request.readBodyAsString();
            })
            .addHandler(Method.GET, "/after", (request, response, pathParams) -> {
                response.status(204);
            })
            .start();

        try (var client = new H2Client();
             var con = client.connect(server)) {
            con.handshake()
                .writeFrame(new Http2HeadersFrame(
                    1,
                    false,
                    requestHeaders("POST", "/slow", server.uri().getPort())
                ))
                .flush();

            var initialHeaders =
                readIgnoringWindowUpdates(con, Http2HeadersFrame.class);
            assertThat(initialHeaders.headers().get(":status"), equalTo("200"));
            assertThat(
                readIgnoringWindowUpdates(con, Http2DataFrame.class).toUTF8(),
                equalTo("partial")
            );
            var reset =
                readIgnoringWindowUpdates(con, Http2ResetStreamFrame.class);
            assertThat(reset.streamId(), equalTo(1));
            assertThat(reset.errorCodeEnum(), equalTo(Http2ErrorCode.CANCEL));

            con.writeFrame(new Http2HeadersFrame(
                    3,
                    true,
                    requestHeaders("GET", "/after", server.uri().getPort())
                ))
                .flush();

            var afterResponse =
                readIgnoringWindowUpdates(con, Http2HeadersFrame.class);
            assertThat(afterResponse.streamId(), equalTo(3));
            assertThat(afterResponse.headers().get(":status"), equalTo("204"));
        }
    }

    private static void drainResponse(
        H2ClientConnection connection,
        Http2HeadersFrame initialHeaders
    ) throws Exception {
        if (initialHeaders.endStream()) {
            return;
        }
        while (true) {
            LogicalHttp2Frame frame = connection.readLogicalFrame();
            if (frame.streamId() == initialHeaders.streamId() && frame.endStream()) {
                return;
            }
        }
    }

    private static FieldBlock requestHeaders(String method, String path, int port) {
        var headers = new FieldBlock();
        headers.add(":scheme", "https");
        headers.add(":authority", "localhost:" + port);
        headers.add(":method", method);
        headers.add(":path", path);
        return headers;
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop();
        }
    }
}
