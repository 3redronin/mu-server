package io.muserver;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static io.muserver.MuServerBuilder.httpsServer;
import static io.muserver.RFCTestUtils.encodeFieldBlock;
import static io.muserver.RFCTestUtils.headersFrame;
import static io.muserver.RFCTestUtils.readIgnoringWindowUpdates;
import static io.muserver.handlers.CORSHandlerBuilder.config;
import static io.muserver.handlers.CORSHandlerBuilder.corsHandler;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorsVaryHttp2Test {
    private @Nullable MuServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop();
    }

    @Test
    void reflectedOriginRemainsInVaryWhenRouteSetsVary() throws Exception {
        server = httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(corsHandler().withCORSConfig(config().withAllOriginsAllowed()))
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.headers().set(HeaderNames.VARY, "Authorization");
                response.write("ok");
            })
            .start();

        FieldBlock requestHeaders = new FieldBlock();
        requestHeaders.add(":method", "GET");
        requestHeaders.add(":scheme", "https");
        requestHeaders.add(":path", "/");
        requestHeaders.add(":authority", "localhost");
        requestHeaders.add("origin", "http://example.org");

        try (var client = new H2Client(); var connection = client.connect(server)) {
            connection.handshake()
                .writeRaw(headersFrame(1, true, true, encodeFieldBlock(requestHeaders)))
                .flush();
            Headers responseHeaders = readIgnoringWindowUpdates(connection, Http2HeadersFrame.class).headers();
            assertEquals("200", responseHeaders.get(":status"));
            assertEquals("http://example.org", responseHeaders.get(HeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN));
            assertTrue(responseHeaders.vary().contains("Authorization", true));
            assertTrue(responseHeaders.vary().contains("Origin", true));
        }
    }
}
