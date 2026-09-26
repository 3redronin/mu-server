package io.muserver;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.muserver.RFCTestUtils.*;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.*;
import static scaffolding.MuAssert.assertEventually;
import static scaffolding.ServerUtils.httpsServerForTest;

@Timeout(20)
class Http2CancellationIsolationTest {

    @Test
    void rapidResetStormDoesNotRetainStreamStateAndConnectionRemainsUsable() throws Exception {
        CompletableFuture<Http2Connection> serverConnection = new CompletableFuture<>();
        try (MuServer server = httpsServerForTest("http")
            .withInterface("localhost")
            .withHttp2Config(Http2ConfigBuilder.http2Enabled().withMaxConcurrentStreams(100))
            .withGzipEnabled(false)
            .addHandler(Method.POST, "/cancelled", (request, response, params) -> {
                serverConnection.complete((Http2Connection) request.connection());
                try {
                    request.inputStream().orElseThrow().readAllBytes();
                    response.status(204);
                } catch (Exception ignored) {
                    // Peer reset released the blocked body read; do not write a response for this stream.
                }
            })
            .addHandler(Method.GET, "/hello", (request, response, params) -> {
                serverConnection.complete((Http2Connection) request.connection());
                response.status(204);
            })
            .start();
             H2Client client = new H2Client();
             H2ClientConnection connection = client.connectClearText(server)) {
            connection.socket().setSoTimeout(5000);
            connection.handshake();

            int streamId = 1;
            for (int i = 0; i < 40; i++, streamId += 2) {
                FieldBlock cancelled = postHeaders(server.uri().getPort(), "/cancelled");
                connection.writeFrame(new Http2HeadersFrame(streamId, false, cancelled))
                    .writeFrame(new Http2ResetStreamFrame(streamId, Http2ErrorCode.CANCEL.code()));
            }
            connection.flush();

            int healthyStream = streamId;
            connection.writeFrame(new Http2HeadersFrame(healthyStream, true,
                getHelloHeaders("http", server.uri().getPort()))).flush();
            Http2HeadersFrame response = readIgnoringWindowUpdates(connection, Http2HeadersFrame.class);
            assertEquals(healthyStream, response.streamId());
            assertEquals("204", response.headers().get(":status"));
            assertTrue(response.endStream());

            Http2Connection serverSide = serverConnection.get(5, TimeUnit.SECONDS);
            assertEventually(() -> serverSide.testProbe().coordinator().resetRecordCount(), is(0));
            assertEventually(() -> serverSide.testProbe().streams().isEmpty(), is(true));

            byte[] ping = ByteBuffer.allocate(8).putLong(99).array();
            connection.writeFrame(new Http2Ping(false, ping)).flush();
            assertEquals(new Http2Ping(true, ping), readIgnoringWindowUpdates(connection, Http2Ping.class));
        }
    }

    @ParameterizedTest
    @CsvSource({"false,1", "true,1", "false,16385", "true,16385"})
    void resetSettlesBlockedWriteOnceAndConnectionServesOtherStreams(boolean tls, int payloadSize) throws Exception {
        CompletableFuture<Throwable> writeResult = new CompletableFuture<>();
        CompletableFuture<ResponseInfo> completion = new CompletableFuture<>();
        CompletableFuture<Http2Connection> serverConnection = new CompletableFuture<>();
        AtomicInteger callbacks = new AtomicInteger();
        AtomicInteger completions = new AtomicInteger();
        try (MuServer server = httpsServerForTest(tls ? "h2" : "http")
            // Match H2Client's loopback hostname under either IPv4 or IPv6 preference.
            .withInterface("localhost")
            .withHttp2Config(Http2ConfigBuilder.http2Enabled().withMaxConcurrentStreams(2))
            .withGzipEnabled(false)
            .addResponseCompleteListener(info -> {
                if (info.request().relativePath().equals("/blocked")) {
                    completions.incrementAndGet();
                    completion.complete(info);
                }
            })
            .addHandler(Method.GET, "/blocked", (request, response, params) -> {
                serverConnection.complete((Http2Connection) request.connection());
                AsyncHandle async = request.handleAsync();
                async.write(ByteBuffer.wrap(new byte[payloadSize]), error -> {
                    callbacks.incrementAndGet();
                    writeResult.complete(error);
                    async.complete(error);
                });
            })
            .addHandler(Method.GET, "/hello", (request, response, params) -> response.status(204))
            .start();
             H2Client client = new H2Client();
             H2ClientConnection connection = tls ? client.connect(server) : client.connectClearText(server)) {
            connection.socket().setSoTimeout(5000);
            FieldBlock blocked = getHelloHeaders(tls ? "https" : "http", server.uri().getPort());
            blocked.set(":path", "/blocked");
            // Zero response DATA credit deterministically holds the asynchronous write.
            connection.handshake(new Http2Settings(false, 4096, 100, 0, 16384, 32768))
                .writeFrame(new Http2HeadersFrame(1, true, blocked)).flush();
            Http2HeadersFrame initial = readIgnoringWindowUpdates(connection, Http2HeadersFrame.class);
            assertEquals(1, initial.streamId());
            assertEquals("200", initial.headers().get(":status"));
            assertFalse(initial.endStream());
            assertFalse(writeResult.isDone());
            healthyResponse(connection, server, tls, 3);
            assertFalse(writeResult.isDone(), "Another stream must finish while the first remains blocked");

            connection.writeFrame(new Http2ResetStreamFrame(1, Http2ErrorCode.CANCEL.code())).flush();
            assertNotNull(writeResult.get(5, TimeUnit.SECONDS), "The blocked writer must receive cancellation");
            assertFalse(completion.get(5, TimeUnit.SECONDS).completedSuccessfully());
            Http2StreamRegistry registry = serverConnection.get(5, TimeUnit.SECONDS).testProbe().streams();
            assertEventually(() -> registry.containsApplicationStream(1), is(false));
            assertEventually(registry::isEmpty, is(true));
            assertEquals(0, registry.concurrentStreamCount());

            byte[] ping = ByteBuffer.allocate(8).putLong(42).array();
            connection.writeFrame(new Http2Ping(false, ping)).flush();
            assertEquals(new Http2Ping(true, ping), readIgnoringWindowUpdates(connection, Http2Ping.class));
            healthyResponse(connection, server, tls, 5);
            assertEventually(registry::isEmpty, is(true));
            assertEquals(1, callbacks.get());
            assertEquals(1, completions.get());
        }
    }

    private static void healthyResponse(H2ClientConnection connection, MuServer server, boolean tls, int stream) throws Exception {
        connection.writeFrame(new Http2HeadersFrame(stream, true,
            getHelloHeaders(tls ? "https" : "http", server.uri().getPort()))).flush();
        Http2HeadersFrame response = readIgnoringWindowUpdates(connection, Http2HeadersFrame.class);
        assertEquals(stream, response.streamId());
        assertEquals("204", response.headers().get(":status"));
        assertTrue(response.endStream());
    }
}
