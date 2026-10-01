package io.muserver;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.muserver.RFCTestUtils.getHelloHeaders;
import static org.junit.jupiter.api.Assertions.*;
import static scaffolding.MuAssert.stopAndCheck;
import static scaffolding.ServerUtils.httpsServerForTest;

class Http2ResponseOutputStreamTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void zeroBufferWritesBeforeFlushOrHandlerReturn(boolean tls) throws Exception {
        var finish = new CountDownLatch(1);
        var server = httpsServerForTest(tls ? "h2" : "http")
            .withHttp2Config(Http2ConfigBuilder.http2Enabled()).withGzipEnabled(false)
            .addHandler((request, response) -> {
                response.headers().set(HeaderNames.CONTENT_LENGTH, 5);
                var out = response.outputStream(0);
                out.write('h');
                out.write("ello".getBytes(StandardCharsets.UTF_8));
                assertTrue(finish.await(5, TimeUnit.SECONDS));
                return true;
            }).start();
        try (var client = new H2Client();
             var connection = tls ? client.connect(server) : client.connectClearText(server)) {
            connection.socket().setSoTimeout(3000);
            connection.handshake().writeFrame(new Http2HeadersFrame(1, true,
                getHelloHeaders(tls ? "https" : "http", server.uri().getPort()))).flush();
            assertEquals("200", connection.readLogicalFrame(Http2HeadersFrame.class).headers().get(":status"));
            var first = connection.readLogicalFrame(Http2DataFrame.class);
            assertArrayEquals(new byte[]{'h'}, first.payload());
            assertFalse(first.endStream());
            var second = connection.readLogicalFrame(Http2DataFrame.class);
            assertArrayEquals("ello".getBytes(StandardCharsets.UTF_8), second.payload());
            assertFalse(second.endStream());
            // Neither flush nor handler return can have released these DATA frames.
            finish.countDown();
            assertTrue(connection.readLogicalFrame(Http2DataFrame.class).endStream());
        } finally {
            finish.countDown();
            stopAndCheck(server);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void negativeBufferIsRejectedBeforeCommittingHeaders(boolean tls) throws Exception {
        var server = httpsServerForTest(tls ? "h2" : "http")
            .withHttp2Config(Http2ConfigBuilder.http2Enabled()).withGzipEnabled(false)
            .addHandler((request, response) -> {
                assertThrows(IllegalArgumentException.class, () -> response.outputStream(-1));
                assertEquals(ResponseState.NOTHING, response.responseState());
                response.status(201);
                response.headers().set("x-recovered", "yes");
                response.write("ok");
                return true;
            }).start();
        try (var client = new H2Client();
             var connection = tls ? client.connect(server) : client.connectClearText(server)) {
            connection.socket().setSoTimeout(3000);
            connection.handshake().writeFrame(new Http2HeadersFrame(1, true,
                getHelloHeaders(tls ? "https" : "http", server.uri().getPort()))).flush();
            var headers = connection.readLogicalFrame(Http2HeadersFrame.class);
            assertEquals("201", headers.headers().get(":status"));
            assertEquals("yes", headers.headers().get("x-recovered"));
            assertArrayEquals("ok".getBytes(StandardCharsets.UTF_8),
                connection.readLogicalFrame(Http2DataFrame.class).payload());
            assertTrue(connection.readLogicalFrame(Http2DataFrame.class).endStream());
        } finally {
            stopAndCheck(server);
        }
    }
}
