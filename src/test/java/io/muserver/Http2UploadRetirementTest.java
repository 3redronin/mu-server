package io.muserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.muserver.RFCTestUtils.getHelloHeaders;
import static io.muserver.RFCTestUtils.readIgnoringWindowUpdates;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.*;
import static scaffolding.MuAssert.assertEventually;
import static scaffolding.ServerUtils.httpsServerForTest;

@Timeout(15)
class Http2UploadRetirementTest {
    @Test
    void writerFailureCompletesAnUnfinishedUploadBeforeRetirement() throws Exception {
        var accepted = new CompletableFuture<Http2Connection>();
        var completed = new CompletableFuture<ResponseInfo>();
        var notifications = new AtomicInteger();
        var server = httpsServerForTest("http")
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .withMaxConcurrentRequests(1)
            .addResponseCompleteListener(info -> {
                notifications.incrementAndGet();
                completed.complete(info);
            })
            .addHandler((request, response) -> {
                accepted.complete((Http2Connection) request.connection());
                response.status(204);
                return true;
            }).start();
        Http2Stream stream = null;
        try (var client = new H2Client(); var con = client.connectClearText(server)) {
            var headers = getHelloHeaders("http", server.uri().getPort());
            headers.set(":method", "POST");
            con.handshake().writeFrame(new Http2HeadersFrame(1, false, headers)).flush();
            var response = readIgnoringWindowUpdates(con, Http2HeadersFrame.class);
            assertEquals("204", response.headers().get(":status"));
            assertTrue(response.endStream());
            var connection = accepted.get(5, TimeUnit.SECONDS);
            stream = connection.testProbe().streams().applicationStream(1);
            assertNotNull(stream);
            assertEventually(stream::applicationExchangeEnded, is(true));
            assertFalse(stream.requestEnded().isDone());
            assertFalse(completed.isDone());
            assertEquals(1, server.stats().activeRequests().size());

            // Keep peer input open while making the next server write fail. The writer
            // must reach its shutdown path before it closes the socket and wakes the reader.
            connection.clientSocket.shutdownOutput();
            connection.write(new Http2Ping(false, new byte[8]));

            assertTrue(stream.requestEnded().handle((ignored, failure) -> true).get(5, TimeUnit.SECONDS),
                "The disconnected upload must end even if the writer shuts down first");
            assertFalse(completed.get(5, TimeUnit.SECONDS).completedSuccessfully());
            assertEquals(1, notifications.get());
            assertEquals(1, server.stats().completedRequests());
            assertTrue(server.stats().activeRequests().isEmpty());
            assertEventually(() -> connection.testProbe().streams().isEmpty(), is(true));

            // Prove that the completed exchange released the server-wide admission slot.
            try (var next = client.connectClearText(server)) {
                next.handshake().writeFrame(new Http2HeadersFrame(1, true,
                    getHelloHeaders("http", server.uri().getPort()))).flush();
                assertEquals("204", readIgnoringWindowUpdates(next, Http2HeadersFrame.class).headers().get(":status"));
            }
        } finally {
            // Release the intentionally unfinished upload even when testing the broken commit.
            if (stream != null && !stream.requestEnded().isDone()) {
                stream.onPeerInputClosed(new java.io.IOException("Test cleanup"));
            }
            server.stop();
        }
    }
}
