package io.muserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.muserver.RFCTestUtils.getHelloHeaders;
import static io.muserver.RFCTestUtils.readIgnoringWindowUpdates;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static scaffolding.ServerUtils.httpsServerForTest;

@Timeout(20)
class Http2MadeYouResetTest {

    @Test
    void locallyResetStreamCountsTowardConcurrencyUntilItsHandlerFinishes() throws Exception {
        var firstHandlerStarted = new CountDownLatch(1);
        var secondHandlerStarted = new CountDownLatch(1);
        var releaseFirstHandler = new CountDownLatch(1);
        var handlersStarted = new AtomicInteger();

        try (MuServer server = httpsServerForTest("http")
            .withInterface("localhost")
            .withHttp2Config(Http2ConfigBuilder.http2Enabled().withMaxConcurrentStreams(1))
            .addHandler(Method.GET, "/blocked", (request, response, pathParams) -> {
                int handlerNumber = handlersStarted.incrementAndGet();
                if (handlerNumber == 1) firstHandlerStarted.countDown();
                else secondHandlerStarted.countDown();
                assertTrue(releaseFirstHandler.await(10, TimeUnit.SECONDS));
                response.status(204);
            })
            .start();
             H2Client client = new H2Client();
             H2ClientConnection connection = client.connectClearText(server)) {
            try {
                connection.socket().setSoTimeout(5000);
                var firstHeaders = getHelloHeaders("http", server.uri().getPort());
                firstHeaders.set(":path", "/blocked");
                connection.handshake()
                    .writeFrame(new Http2HeadersFrame(1, true, firstHeaders))
                    .flush();
                assertTrue(firstHandlerStarted.await(5, TimeUnit.SECONDS));

                // A zero stream WINDOW_UPDATE makes the server reset the stream. The reset must
                // not let another request bypass the configured concurrency limit while the
                // first request's application work is still running.
                connection.writeFrame(new Http2WindowUpdate(1, 0)).flush();
                var firstReset = readIgnoringWindowUpdates(connection, Http2ResetStreamFrame.class);
                assertEquals(1, firstReset.streamId());
                assertEquals(Http2ErrorCode.PROTOCOL_ERROR, firstReset.errorCodeEnum());

                var secondHeaders = getHelloHeaders("http", server.uri().getPort());
                secondHeaders.set(":path", "/blocked");
                connection.writeFrame(new Http2HeadersFrame(3, true, secondHeaders)).flush();

                assertFalse(secondHandlerStarted.await(250, TimeUnit.MILLISECONDS),
                    "A locally reset stream must not free its concurrency slot while handler work is still active");
                var refused = readIgnoringWindowUpdates(connection, Http2ResetStreamFrame.class);
                assertEquals(3, refused.streamId());
                assertEquals(Http2ErrorCode.REFUSED_STREAM, refused.errorCodeEnum());
                assertEquals(1, handlersStarted.get());
            } finally {
                releaseFirstHandler.countDown();
            }
        }
    }
}
