package io.muserver;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import scaffolding.Http1Client;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import java.io.BufferedInputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static scaffolding.ClientUtils.veryTrustingTrustManager;
import static scaffolding.ServerUtils.httpsServerForTest;

/** Bounded valid-traffic lifecycle checks. Socket buffers induce backpressure, not a throughput target. */
@Timeout(90)
class LifecycleRecoveryTest {
    static Stream<Arguments> cases() {
        var cases = new ArrayList<Arguments>();
        for (String protocol : List.of("http1", "https", "h2", "h2-socket", "ws", "wss")) {
            for (boolean supplied : new boolean[]{false, true}) {
                for (String ending : (protocol.startsWith("ws") ? List.of("disconnect", "shutdown", "timeout", "idle-timeout") : List.of("disconnect", "shutdown", "timeout"))) {
                    cases.add(Arguments.of(protocol, supplied, ending));
                }
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}, supplied={1}, {2}") @MethodSource("cases")
    void pendingWritesAndExecutorsAreReleased(String protocol, boolean supplied, String ending) throws Exception {
        boolean websocket = protocol.startsWith("ws");
        boolean tls = protocol.startsWith("h2") || protocol.equals("https") || protocol.equals("wss");
        var application = supplied ? new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), r -> new Thread(r, "lifecycle-caller")) : null;
        var resources = new AtomicReference<ExecutionResources>();
        var submitted = new CountDownLatch(1);
        var callback = new CompletableFuture<Throwable>();
        var writeFuture = new AtomicReference<Future<?>>();
        var session = new AtomicReference<MuWebSocketSession>();
        var callbacks = new AtomicInteger();
        var completions = new AtomicInteger();
        var completed = new CompletableFuture<Boolean>();
        var payload = new byte[512 * 1024];
        new java.util.Random(873).nextBytes(payload);
        DoneCallback done = error -> {
            callbacks.incrementAndGet();
            callback.complete(error);
        };
        var builder = httpsServerForTest(tls ? (protocol.startsWith("h2") ? "h2" : "https") : "http")
            .withHandlerExecutor(application)
            .withIdleTimeout((ending.equals("timeout") && !websocket) || ending.equals("idle-timeout") ? 3 : 60, TimeUnit.SECONDS)
            .withRequestTimeout(ending.equals("timeout") ? 3 : 60, TimeUnit.SECONDS)
            .addResponseCompleteListener(info -> {
                if (info.request().uri().getPath().equals("/work") && !websocket) {
                    completions.incrementAndGet();
                    completed.complete(info.completedSuccessfully());
                }
            })
            .addHandler((req, resp) -> {
                if (req.uri().getPath().equals("/probe")) { resp.write("ok"); return true; }
                ((BaseHttpConnection) req.connection()).clientSocket.setSendBufferSize(8192);
                return false;
            });
        builder.executionResourcesFactory = executor -> {
            var created = ExecutionResources.create(executor);
            resources.set(created);
            return created;
        };
        if (websocket) {
            builder.addHandler(WebSocketHandlerBuilder.webSocketHandler((req, headers) -> new SimpleWebSocket() {
                @Override public void onConnect(MuWebSocketSession connected) throws Exception {
                    super.onConnect(connected);
                    session.set(connected);
                }
                @Override public void onText(String message) {
                    session().sendBinary(ByteBuffer.wrap(payload), done);
                    submitted.countDown();
                }
                @Override public void onBinary(ByteBuffer message) { }
            }).withPath("/work").withPingInterval(0, TimeUnit.SECONDS)
                .withIdleReadTimeout(ending.equals("timeout") ? 3 : 60, TimeUnit.SECONDS));
        } else {
            builder.addHandler((req, resp) -> {
                resp.contentType("application/octet-stream");
                AsyncHandle handle = req.handleAsync();
                writeFuture.set(handle.write(ByteBuffer.wrap(payload)));
                handle.write(ByteBuffer.wrap(new byte[]{42}), done);
                handle.complete();
                submitted.countDown();
                return true;
            });
        }
        MuServer server = builder.start();
        try (Peer peer = new Peer(server, protocol)) {
            peer.start(websocket);
            assertTrue(submitted.await(20, TimeUnit.SECONDS), "Application did not submit its write");
            peer.observeOutputStarted(websocket);
            assertFalse(callback.isDone(), "Response fitted in the buffers; backpressure was not exercised");
            if (!websocket) assertFalse(writeFuture.get().isDone());
            probe(server);
            if (ending.equals("disconnect")) peer.abort();
            else if (ending.equals("shutdown")) assertFalse(server.stop(0, TimeUnit.SECONDS));
            // The configured timeout supplies the third termination trigger.
            assertNotNull(callback.get(25, TimeUnit.SECONDS), "Unfinished write must fail");
            if (websocket) {
                await(() -> session.get().state().endState(), "WebSocket did not reach an end state");
                if (ending.endsWith("timeout")) assertEquals(WebsocketSessionState.TIMED_OUT, session.get().state());
            } else {
                assertThrows(ExecutionException.class, () -> writeFuture.get().get(20, TimeUnit.SECONDS));
                assertFalse(completed.get(20, TimeUnit.SECONDS), "Incomplete response reported success");
            }
            await(() -> server.stats().activeRequests().isEmpty(), "Requests did not drain");
            await(() -> server.activeConnections().isEmpty(), "Connections did not retire");
            if (!ending.equals("shutdown")) probe(server);
        } finally {
            var owned = resources.get();
            try {
                server.stop(0, TimeUnit.SECONDS);
                assertTrue(owned.internal.awaitTermination(20, TimeUnit.SECONDS), "Internal executor retained work");
                assertTrue(owned.timer.awaitTermination(20, TimeUnit.SECONDS), "Timer retained work");
                if (supplied) {
                    assertFalse(application.isShutdown(), "Mu shut down a caller-owned executor");
                    assertEquals("available", application.submit(() -> "available").get(20, TimeUnit.SECONDS));
                } else {
                    assertTrue(owned.application.awaitTermination(20, TimeUnit.SECONDS), "Owned application executor retained work");
                }
            } finally {
                // A failed assertion must not leave server workers running in the test JVM.
                owned.internal.shutdownNow();
                owned.timer.shutdownNow();
                if (!supplied) owned.application.shutdownNow();
                if (application != null) { application.shutdownNow(); assertTrue(application.awaitTermination(20, TimeUnit.SECONDS)); }
            }
        }
        assertEquals(1, callbacks.get(), "Write callback must be delivered once");
        if (!websocket) assertEquals(1, completions.get(), "Response completion must be delivered once");
    }

    private static void probe(MuServer server) throws Exception {
        try (var client = Http1Client.connect(server)) {
            client.writeRequestLine(Method.GET, "/probe").writeHeader("connection", "close").flushHeaders();
            assertEquals("HTTP/1.1 200 OK", client.readLine());
            assertEquals("ok", client.readBody(client.readHeaders()));
        }
    }

    private static void await(BooleanSupplier condition, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        do { if (condition.getAsBoolean()) return; Thread.sleep(10); } while (System.nanoTime() < deadline);
        fail(message);
    }

    private static class Peer implements AutoCloseable {
        final Socket socket;
        final Http1Client http;
        final H2ClientConnection h2;
        final MuServer server;

        Peer(MuServer server, String protocol) throws Exception {
            this.server = server;
            if (protocol.startsWith("h2")) {
                h2 = new H2Client().connect(server); socket = h2.socket(); http = null;
                socket.setReceiveBufferSize(8192);
                boolean socketBackpressure = protocol.equals("h2-socket");
                h2.handshake(new Http2Settings(false, 4096, 100, socketBackpressure ? 1024 * 1024 : 0, 16384, 32768));
                if (socketBackpressure) h2.writeFrame(new Http2WindowUpdate(0, 1024 * 1024)).flush();
            } else {
                h2 = null;
                Socket raw = new Socket();
                raw.setReceiveBufferSize(8192);
                raw.connect(new InetSocketAddress(server.uri().getHost(), server.uri().getPort()), 20000);
                if (protocol.equals("wss") || protocol.equals("https")) {
                    SSLContext context = SSLContext.getInstance("TLS");
                    context.init(null, new TrustManager[]{veryTrustingTrustManager()}, null);
                    socket = context.getSocketFactory().createSocket(raw, server.uri().getHost(), server.uri().getPort(), true);
                } else socket = raw;
                http = new Http1Client(socket, new BufferedInputStream(socket.getInputStream()), socket.getOutputStream(), server.uri());
            }
            socket.setSoTimeout(20000);
        }

        void start(boolean websocket) throws Exception {
            if (h2 != null) {
                var headers = RFCTestUtils.getHelloHeaders(server.uri().getPort()); headers.set(":path", "/work");
                h2.writeFrame(new Http2HeadersFrame(1, true, headers)).flush();
            } else {
                http.writeRequestLine(Method.GET, "/work");
                if (websocket) http.writeHeader("upgrade", "websocket").writeHeader("connection", "Upgrade")
                    .writeHeader("Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ==").writeHeader("Sec-WebSocket-Version", "13");
                http.flushHeaders();
                if (websocket) {
                    assertEquals("HTTP/1.1 101 Switching Protocols", http.readLine()); http.readHeaders();
                    http.out().write(new byte[]{(byte) 0x81, (byte) 0x84, 0, 0, 0, 0, 's', 'e', 'n', 'd'}); http.flush();
                }
            }
        }

        void observeOutputStarted(boolean websocket) throws Exception {
            if (h2 != null) {
                var headers = RFCTestUtils.readIgnoringWindowUpdates(h2, Http2HeadersFrame.class);
                assertEquals("200", headers.headers().get(":status"));
            } else if (websocket) {
                assertEquals(0x82, http.in().read());
                assertEquals(127, http.in().read());
            } else {
                assertEquals("HTTP/1.1 200 OK", http.readLine()); http.readHeaders();
            }
        }
        void abort() throws Exception { socket.setSoLinger(true, 0); socket.close(); }
        @Override public void close() throws Exception { socket.close(); }
    }
}
