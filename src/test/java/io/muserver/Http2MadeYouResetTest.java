package io.muserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.muserver.RFCTestUtils.getHelloHeaders;
import static io.muserver.RFCTestUtils.readIgnoringWindowUpdates;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static scaffolding.MuAssert.assertEventually;
import static scaffolding.ServerUtils.httpsServerForTest;

@Timeout(20)
class Http2MadeYouResetTest {

    @Test
    void locallyResetStreamCountsTowardConcurrencyUntilItsHandlerFinishes() throws Exception {
        var firstHandlerStarted = new CountDownLatch(1);
        var releaseFirstHandler = new CountDownLatch(1);
        var firstExchangeCompleted = new CountDownLatch(1);
        var handlersStarted = new AtomicInteger();

        try (MuServer server = httpsServerForTest("http")
            .withInterface("localhost")
            .withHttp2Config(Http2ConfigBuilder.http2Enabled().withMaxConcurrentStreams(1))
            .withMaxConcurrentRequests(0)
            .addResponseCompleteListener(info -> {
                if (info.request().relativePath().equals("/blocked")) firstExchangeCompleted.countDown();
            })
            .addHandler(Method.GET, "/blocked", (request, response, pathParams) -> {
                handlersStarted.incrementAndGet();
                firstHandlerStarted.countDown();
                assertTrue(releaseFirstHandler.await(10, TimeUnit.SECONDS));
                response.status(204);
            })
            .addHandler(Method.GET, "/healthy", (request, response, pathParams) -> response.status(204))
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
                var registry = ((Http2Connection) server.activeConnections().iterator().next())
                    .testProbe().streams();
                assertEquals(1, registry.admissionCapacityUsed());

                // A zero stream WINDOW_UPDATE makes the server reset the stream. The reset must
                // not let another request bypass the configured concurrency limit while the
                // first request's application work is still running.
                connection.writeFrame(new Http2WindowUpdate(1, 0)).flush();
                var firstReset = readIgnoringWindowUpdates(connection, Http2ResetStreamFrame.class);
                assertEquals(1, firstReset.streamId());
                assertEquals(Http2ErrorCode.PROTOCOL_ERROR, firstReset.errorCodeEnum());
                assertEquals(1, registry.admissionCapacityUsed());

                var secondHeaders = getHelloHeaders("http", server.uri().getPort());
                secondHeaders.set(":path", "/blocked");
                connection.writeFrame(new Http2HeadersFrame(3, true, secondHeaders)).flush();

                var refused = readIgnoringWindowUpdates(connection, Http2ResetStreamFrame.class);
                assertEquals(3, refused.streamId());
                assertEquals(Http2ErrorCode.REFUSED_STREAM, refused.errorCodeEnum());
                assertEquals(1, handlersStarted.get());

                try (H2ClientConnection otherConnection = client.connectClearText(server)) {
                    otherConnection.socket().setSoTimeout(5000);
                    var healthyHeaders = getHelloHeaders("http", server.uri().getPort());
                    healthyHeaders.set(":path", "/healthy");
                    otherConnection.handshake()
                        .writeFrame(new Http2HeadersFrame(1, true, healthyHeaders)).flush();
                    var healthy = readIgnoringWindowUpdates(otherConnection, Http2HeadersFrame.class);
                    assertEquals(1, healthy.streamId());
                    assertEquals("204", healthy.headers().get(":status"));
                }

                releaseFirstHandler.countDown();
                assertTrue(firstExchangeCompleted.await(5, TimeUnit.SECONDS));
                assertEquals(0, registry.admissionCapacityUsed());
                var healthyHeaders = getHelloHeaders("http", server.uri().getPort());
                healthyHeaders.set(":path", "/healthy");
                connection.writeFrame(new Http2HeadersFrame(5, true, healthyHeaders)).flush();
                var recovered = readIgnoringWindowUpdates(connection, Http2HeadersFrame.class);
                assertEquals(5, recovered.streamId());
                assertEquals("204", recovered.headers().get(":status"));
            } finally {
                releaseFirstHandler.countDown();
            }
        }
    }

    @Test
    void resetQueuedBeforeHandlerStartsKeepsCapacityUntilQueuedCleanupFinishes() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        var blockerStarted = new CountDownLatch(1);
        var releaseBlocker = new CountDownLatch(1);
        var exchangeCompleted = new CountDownLatch(1);
        var handlersStarted = new AtomicInteger();
        executor.submit(() -> {
            blockerStarted.countDown();
            releaseBlocker.await();
            return null;
        });
        try {
            assertTrue(blockerStarted.await(5, TimeUnit.SECONDS));
            try (MuServer server = httpsServerForTest("http")
                .withInterface("localhost")
                .withHandlerExecutor(executor)
                .withHttp2Config(Http2ConfigBuilder.http2Enabled().withMaxConcurrentStreams(1))
                .addResponseCompleteListener(info -> exchangeCompleted.countDown())
                .addHandler(Method.GET, "/blocked", (request, response, params) -> {
                    handlersStarted.incrementAndGet();
                    response.status(204);
                })
                .addHandler(Method.GET, "/healthy", (request, response, params) -> response.status(204))
                .start();
                 H2Client client = new H2Client();
                 H2ClientConnection connection = client.connectClearText(server)) {
                connection.socket().setSoTimeout(5000);
                connection.handshake()
                    .writeFrame(headers(server, 1, "/blocked")).flush();
                var registry = ((Http2Connection) server.activeConnections().iterator().next())
                    .testProbe().streams();
                assertEventually(registry::admissionCapacityUsed, is(1L));

                connection.writeFrame(new Http2ResetStreamFrame(1, Http2ErrorCode.CANCEL.code()))
                    .writeFrame(headers(server, 3, "/healthy")).flush();
                assertRefused(connection, 3);
                assertEquals(0, handlersStarted.get());
                assertEquals(1, registry.admissionCapacityUsed());

                releaseBlocker.countDown();
                assertTrue(exchangeCompleted.await(5, TimeUnit.SECONDS));
                assertEquals(0, registry.admissionCapacityUsed());
                connection.writeFrame(headers(server, 5, "/healthy")).flush();
                assertAccepted(connection, 5);
                assertEquals(0, handlersStarted.get());
            }
        } finally {
            releaseBlocker.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void resetAsyncExchangeKeepsCapacityUntilItsCleanupRuns() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        var asyncStarted = new CountDownLatch(1);
        var blockerStarted = new CountDownLatch(1);
        var releaseBlocker = new CountDownLatch(1);
        var exchangeCompleted = new CountDownLatch(1);
        try {
            try (MuServer server = httpsServerForTest("http")
                .withInterface("localhost")
                .withHandlerExecutor(executor)
                .withHttp2Config(Http2ConfigBuilder.http2Enabled().withMaxConcurrentStreams(1))
                .addResponseCompleteListener(info -> exchangeCompleted.countDown())
                .addHandler(Method.GET, "/async", (request, response, params) -> {
                    request.handleAsync();
                    asyncStarted.countDown();
                })
                .addHandler(Method.GET, "/healthy", (request, response, params) -> response.status(204))
                .start();
                 H2Client client = new H2Client();
                 H2ClientConnection connection = client.connectClearText(server)) {
                connection.socket().setSoTimeout(5000);
                connection.handshake()
                    .writeFrame(headers(server, 1, "/async")).flush();
                assertTrue(asyncStarted.await(5, TimeUnit.SECONDS));
                executor.submit(() -> {
                    blockerStarted.countDown();
                    releaseBlocker.await();
                    return null;
                });
                assertTrue(blockerStarted.await(5, TimeUnit.SECONDS));

                var registry = ((Http2Connection) server.activeConnections().iterator().next())
                    .testProbe().streams();
                connection.writeFrame(new Http2ResetStreamFrame(1, Http2ErrorCode.CANCEL.code()))
                    .writeFrame(headers(server, 3, "/healthy")).flush();
                assertRefused(connection, 3);
                assertEquals(1, registry.admissionCapacityUsed());

                releaseBlocker.countDown();
                assertTrue(exchangeCompleted.await(5, TimeUnit.SECONDS));
                assertEquals(0, registry.admissionCapacityUsed());
                connection.writeFrame(headers(server, 5, "/healthy")).flush();
                assertAccepted(connection, 5);
            }
        } finally {
            releaseBlocker.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void completedResponseStillCountsUntilItsHandlerReturns() throws Exception {
        var responseEnded = new CountDownLatch(1);
        var releaseHandler = new CountDownLatch(1);
        var exchangeCompleted = new CountDownLatch(1);
        var releaseCompletionListener = new CountDownLatch(1);
        try (MuServer server = httpsServerForTest("http")
            .withInterface("localhost")
            .withHttp2Config(Http2ConfigBuilder.http2Enabled().withMaxConcurrentStreams(1))
            .addResponseCompleteListener(info -> {
                if (info.request().relativePath().equals("/blocked")) {
                    exchangeCompleted.countDown();
                    try {
                        releaseCompletionListener.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            })
            .addHandler(Method.GET, "/blocked", (request, response, params) -> {
                try (var output = response.outputStream(1)) {
                    output.write('x');
                }
                responseEnded.countDown();
                assertTrue(releaseHandler.await(10, TimeUnit.SECONDS));
            })
            .addHandler(Method.GET, "/healthy", (request, response, params) -> response.status(204))
            .start();
             H2Client client = new H2Client();
             H2ClientConnection connection = client.connectClearText(server)) {
            try {
                connection.socket().setSoTimeout(5000);
                connection.handshake()
                    .writeFrame(headers(server, 1, "/blocked")).flush();
                assertTrue(responseEnded.await(5, TimeUnit.SECONDS));
                readIgnoringWindowUpdates(connection, Http2HeadersFrame.class);
                readIgnoringWindowUpdates(connection, Http2DataFrame.class);
                assertTrue(readIgnoringWindowUpdates(connection, Http2DataFrame.class).endStream());

                var registry = ((Http2Connection) server.activeConnections().iterator().next())
                    .testProbe().streams();
                assertEventually(() -> registry.applicationStream(1).protocolStateClosed(), is(true));
                assertEquals(1, registry.admissionCapacityUsed());
                connection.writeFrame(headers(server, 3, "/healthy")).flush();
                assertRefused(connection, 3);

                releaseHandler.countDown();
                assertTrue(exchangeCompleted.await(5, TimeUnit.SECONDS));
                assertEquals(0, registry.admissionCapacityUsed());
                connection.writeFrame(headers(server, 5, "/healthy")).flush();
                assertAccepted(connection, 5);
            } finally {
                releaseHandler.countDown();
                releaseCompletionListener.countDown();
            }
        }
    }

    private static Http2HeadersFrame headers(MuServer server, int streamId, String path) {
        var headers = getHelloHeaders("http", server.uri().getPort());
        headers.set(":path", path);
        return new Http2HeadersFrame(streamId, true, headers);
    }

    private static void assertRefused(H2ClientConnection connection, int streamId) throws Exception {
        var refused = readIgnoringWindowUpdates(connection, Http2ResetStreamFrame.class);
        assertEquals(streamId, refused.streamId());
        assertEquals(Http2ErrorCode.REFUSED_STREAM, refused.errorCodeEnum());
    }

    private static void assertAccepted(H2ClientConnection connection, int streamId) throws Exception {
        var response = readIgnoringWindowUpdates(connection, Http2HeadersFrame.class);
        assertEquals(streamId, response.streamId());
        assertEquals("204", response.headers().get(":status"));
    }
}
