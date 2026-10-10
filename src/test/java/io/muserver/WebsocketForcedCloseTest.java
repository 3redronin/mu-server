package io.muserver;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static scaffolding.ServerUtils.httpsServerForTest;

@Timeout(20)
class WebsocketForcedCloseTest {
    static Stream<Arguments> forcedCloses() {
        return Stream.of(false, true).flatMap(tls ->
            Stream.of("stop", "connection-abort", "timeout", "force-shutdown")
                .map(action -> Arguments.of(tls, action)));
    }

    @ParameterizedTest(name = "tls={0}, action={1}")
    @MethodSource("forcedCloses")
    void forcedTransportCloseReleasesAnUnfinishedReceive(boolean tls, String action) throws Exception {
        var application = Executors.newSingleThreadExecutor();
        var internal = Executors.newCachedThreadPool();
        var timer = Executors.newSingleThreadScheduledExecutor();
        var resources = new ExecutionResources(application, true, internal, timer);
        var pendingReceive = new CompletableFuture<DoneCallback>();
        var connected = new CompletableFuture<MuWebSocketSession>();
        var parent = new CompletableFuture<Http1Connection>();
        var errors = new AtomicInteger();
        var handler = new BaseWebSocket() {
            @Override public void onConnect(MuWebSocketSession session) throws Exception {
                super.onConnect(session);
                connected.complete(session);
            }
            @Override public void onBinary(ByteBuffer data, boolean last, DoneCallback completion) {
                pendingReceive.complete(completion);
            }
            @Override public void onError(Throwable cause) { errors.incrementAndGet(); }
        };
        var builder = httpsServerForTest(tls ? "https" : "http")
            .addHandler(WebSocketHandlerBuilder.webSocketHandler((request, headers) -> {
                parent.complete((Http1Connection) request.connection());
                return handler;
            }).withPingInterval(0, TimeUnit.SECONDS));
        builder.executionResourcesFactory = supplied -> resources;
        DoneCallback completion = null;
        try (var server = builder.start(); var peer = new WebSocketWireTestSupport(server)) {
            MuWebSocketSession session = connected.get(5, TimeUnit.SECONDS);
            peer.send(true, 2, new byte[]{1, 2, 3});
            completion = pendingReceive.get(5, TimeUnit.SECONDS);
            Http1Connection connection = parent.get(5, TimeUnit.SECONDS);
            switch (action) {
                case "stop":
                    assertTimeoutPreemptively(java.time.Duration.ofSeconds(5),
                        () -> server.stop(0, TimeUnit.SECONDS));
                    break;
                case "connection-abort": connection.abort(); break;
                case "timeout": connection.abortWithTimeout(); break;
                case "force-shutdown": connection.forceShutdown(); break;
                default: throw new AssertionError(action);
            }
            assertEquals(-1, peer.input.read(), "Forced closure must not write a close frame");
            if (!action.equals("stop")) server.stop(0, TimeUnit.SECONDS);
            assertTrue(internal.awaitTermination(3, TimeUnit.SECONDS),
                "The internal reader must retire before receive completion");
            assertTrue(application.awaitTermination(3, TimeUnit.SECONDS),
                "The owned application executor must terminate after internal I/O");
            assertTrue(timer.awaitTermination(3, TimeUnit.SECONDS));
            assertEquals(0, server.activeConnections().size());
            assertTrue(session.state().endState());
            if (action.equals("timeout")) assertEquals(WebsocketSessionState.TIMED_OUT, session.state());
            assertThrows(IOException.class, () -> session.sendBinary(ByteBuffer.wrap(new byte[]{4})));
            // No application worker is waiting: only the deferred receive completion was withheld.
            assertEquals(0, errors.get());
            completion.onComplete(null);
            assertTrue(session.state().endState());
        } finally {
            if (completion != null) completion.onComplete(null);
            internal.shutdownNow();
            application.shutdownNow();
            timer.shutdownNow();
            assertTrue(internal.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(application.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(timer.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
