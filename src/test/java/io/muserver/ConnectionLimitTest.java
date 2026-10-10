package io.muserver;

import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import scaffolding.Http1Client;

import javax.net.ssl.SSLSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.muserver.MuServerBuilder.*;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.*;
import static scaffolding.ClientUtils.*;
import static scaffolding.MuAssert.assertEventually;

/** Small lifecycle checks; timeouts bound failures, not throughput or capacity measurements. */
@Timeout(20)
class ConnectionLimitTest {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void idleKeepAliveOccupiesASlotUntilDisconnect(boolean tls) throws Exception {
        AtomicInteger handled = new AtomicInteger();
        try (MuServer server = responding(tls ? httpsServer() : httpServer(), handled).withMaxConnections(1).start();
             Socket first = connect(server.uri())) {
            exchange(first, server.uri(), false);
            assertEquals(1, handled.get());
            assertEquals(1, count(server));
            assertEventually(() -> server.stats().activeRequests().size(), equalTo(0));
            // Use a raw peer so a TLS handshake can be queued without blocking this test thread.
            try (Socket queued = raw(server.uri())) {
                queued.setSoTimeout(150);
                assertThrows(SocketTimeoutException.class, () -> queued.getInputStream().read());
                assertEquals(1, count(server));
                first.close();
                try (Socket next = negotiate(queued, server.uri())) {
                    exchange(next, server.uri(), true);
                }
            }
            assertEventually(() -> count(server), equalTo(0));
            assertEquals(2, handled.get());
        }
    }

    @Test void silentTlsSetupCountsBeforeAnyHttpConnectionOrHandler() throws Exception {
        AtomicInteger handled = new AtomicInteger();
        try (MuServer server = responding(httpsServer(), handled).withMaxConnections(1).start();
             Socket silent = raw(server.uri())) {
            Mu3ServerImpl impl = (Mu3ServerImpl) server;
            assertEventually(impl::pendingAcceptedSocketCount, equalTo(1));
            assertEquals(1, count(server));
            assertTrue(server.activeConnections().isEmpty());
            assertEquals(0, handled.get());
            try (Socket queued = raw(server.uri())) {
                silent.close();
                try (Socket accepted = negotiate(queued, server.uri())) { exchange(accepted, server.uri(), true); }
            }
            assertEventually(() -> count(server), equalTo(0));
            assertEventually(impl::pendingAcceptedSocketCount, equalTo(0));
            assertEquals(1, handled.get());
        }
    }

    @Test void idleListenersDoNotReserveSharedCapacity() throws Exception {
        try (MuServer server = responding(muServer().withHttpPort(0).withHttpsPort(0), new AtomicInteger())
            .withMaxConnections(1).withListenBacklog(7).start()) {
            for (URI uri : new URI[]{server.httpUri(), server.httpsUri(), server.httpUri(), server.httpsUri()}) {
                try (Socket connection = connect(uri)) {
                    exchange(connection, uri, false);
                    assertEquals(1, count(server));
                }
                assertEventually(() -> count(server), equalTo(0));
            }
        }
    }

    @Test void fullHttpListenerCannotAdmitSetupOnTheHttpsListener() throws Exception {
        try (MuServer server = responding(muServer().withHttpPort(0).withHttpsPort(0), new AtomicInteger())
            .withMaxConnections(1).start(); Socket first = connect(server.httpUri())) {
            exchange(first, server.httpUri(), false);
            // HTTPS may already be inside accept(). Its candidate must be rejected before TLS setup;
            // alternatively it has observed the full limit and leaves the candidate in the backlog.
            try (Socket candidate = raw(server.httpsUri())) {
                candidate.setSoTimeout(150);
                try { assertEquals(-1, candidate.getInputStream().read()); }
                catch (SocketTimeoutException | SocketException expected) { /* backlog or reset */ }
                assertEquals(1, count(server));
                assertEquals(0, ((Mu3ServerImpl) server).pendingAcceptedSocketCount());
            }
        }
    }

    @Test void multipleConcurrentHttp2StreamsUseOneConnectionSlot() throws Exception {
        var handles = new LinkedBlockingQueue<AsyncHandle>();
        try (MuServer server = httpServer().withMaxConnections(1).withMaxConcurrentRequests(2)
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler((req, resp) -> { handles.add(req.handleAsync()); return true; }).start();
             var h2 = new H2Client(); var connection = h2.connectClearText(server)) {
            connection.socket().setSoTimeout(3000);
            connection.handshake();
            for (int stream : new int[]{1, 3}) connection.writeFrame(new Http2HeadersFrame(stream, true,
                RFCTestUtils.getHelloHeaders("http", server.uri().getPort()))).flush();
            AsyncHandle first = handles.poll(5, TimeUnit.SECONDS);
            AsyncHandle second = handles.poll(5, TimeUnit.SECONDS);
            try {
                assertNotNull(first);
                assertNotNull(second);
                assertEquals(1, count(server));
                assertEquals(2, server.stats().activeRequests().size());
            } finally {
                if (first != null) first.complete();
                if (second != null) second.complete();
            }
            connection.readLogicalFrame(Http2HeadersFrame.class);
            connection.readLogicalFrame(Http2HeadersFrame.class);
            assertEventually(() -> server.stats().activeRequests().size(), equalTo(0));
            assertEquals(1, count(server), "Completing streams must not release the connection slot");
            connection.close();
            assertEventually(() -> count(server), equalTo(0));
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void webSocketUpgradeRetainsAdmissionUntilClosure(boolean tls) throws Exception {
        var opened = new CompletableFuture<Void>();
        try (MuServer server = responding((tls ? httpsServer() : httpServer())
            .addHandler(WebSocketHandlerBuilder.webSocketHandler((req, headers) -> new SimpleWebSocket() {
                @Override public void onText(String message) { }
                @Override public void onBinary(java.nio.ByteBuffer message) { }
            }).withPath("/ws")),
            new AtomicInteger()).withMaxConnections(1).start()) {
            WebSocket ws = client.newWebSocket(request().url("ws" + server.uri().resolve("/ws").toString().substring(4)).build(),
                new WebSocketListener() {
                    @Override public void onOpen(WebSocket webSocket, Response response) { opened.complete(null); }
                    @Override public void onFailure(WebSocket webSocket, Throwable error, Response response) {
                        opened.completeExceptionally(error);
                    }
                });
            try {
                opened.get(5, TimeUnit.SECONDS);
                assertEventually(() -> server.stats().activeRequests().size(), equalTo(0));
                assertEquals(1, count(server));
                try (Socket queued = raw(server.uri())) {
                    queued.setSoTimeout(150);
                    assertThrows(SocketTimeoutException.class, () -> queued.getInputStream().read());
                    assertTrue(ws.close(1000, "done"));
                    try (Socket next = negotiate(queued, server.uri())) { exchange(next, server.uri(), true); }
                }
                assertEventually(() -> count(server), equalTo(0));
            } finally { ws.cancel(); }
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void idleTimeoutReleasesAdmission(boolean tls) throws Exception {
        try (MuServer server = responding(tls ? httpsServer() : httpServer(), new AtomicInteger())
            .withMaxConnections(1).withIdleTimeout(150, TimeUnit.MILLISECONDS).start();
             Socket first = connect(server.uri())) {
            exchange(first, server.uri(), false);
            assertEventually(() -> count(server), equalTo(0));
            try (Socket next = connect(server.uri())) { exchange(next, server.uri(), true); }
            assertEventually(() -> count(server), equalTo(0));
        }
    }

    @Test void proxySetupTimeoutReleasesAdmissionAndNextValidClientSucceeds() throws Exception {
        try (MuServer server = responding(httpServer(), new AtomicInteger()).withMaxConnections(1)
            .withHAProxyProtocolConfig(HAProxyProtocolConfigBuilder.config().withEnabled(true)
                .withTimeout(1, TimeUnit.SECONDS)).start(); Socket silent = raw(server.uri())) {
            assertEventually(() -> ((Mu3ServerImpl) server).pendingPreambleCount(), equalTo(1));
            assertEquals(1, count(server));
            assertEquals(-1, silent.getInputStream().read());
            assertEventually(() -> count(server), equalTo(0));
            try (Socket next = raw(server.uri())) {
                next.getOutputStream().write("PROXY TCP4 192.0.2.1 198.51.100.2 12345 80\r\n"
                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                exchange(next, server.uri(), true);
            }
            assertEventually(() -> count(server), equalTo(0));
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void shutdownWakesFullListenersAndClosesSetupOrIdleConnections(boolean setup) throws Exception {
        MuServer server = responding(muServer().withHttpPort(0).withHttpsPort(0), new AtomicInteger())
            .withMaxConnections(1).start();
        try (Socket first = setup ? raw(server.httpsUri()) : connect(server.httpUri())) {
            if (setup) assertEventually(() -> ((Mu3ServerImpl) server).pendingAcceptedSocketCount(), equalTo(1));
            else exchange(first, server.httpUri(), false);
            assertEquals(1, count(server));
            assertTrue(server.stop(3, TimeUnit.SECONDS));
            assertEventually(() -> count(server), equalTo(0));
            assertEquals(0, ((Mu3ServerImpl) server).pendingAcceptedSocketCount());
            assertTrue(server.activeConnections().isEmpty());
            assertEquals(-1, first.getInputStream().read());
        } finally { server.stop(0, TimeUnit.MILLISECONDS); }
    }

    private static int count(MuServer server) { return ((Mu3ServerImpl) server).connectionAdmission.admittedCount(); }

    private static MuServerBuilder responding(MuServerBuilder builder, AtomicInteger handled) {
        return builder.addHandler((req, resp) -> { handled.incrementAndGet(); resp.write("ok"); return true; });
    }

    private static Socket raw(URI uri) throws Exception {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("localhost", uri.getPort()), 3000);
            socket.setSoTimeout(3000);
            return socket;
        } catch (Exception e) { socket.close(); throw e; }
    }

    private static Socket connect(URI uri) throws Exception { return negotiate(raw(uri), uri); }

    private static Socket negotiate(Socket raw, URI uri) throws Exception {
        raw.setSoTimeout(3000);
        if (!uri.getScheme().equals("https")) return raw;
        try {
            SSLSocket secure = (SSLSocket) sslContextForTesting(veryTrustingTrustManager).getSocketFactory()
                .createSocket(raw, "localhost", uri.getPort(), true);
            secure.setSoTimeout(3000);
            secure.startHandshake();
            return secure;
        } catch (Exception e) { raw.close(); throw e; }
    }

    private static void exchange(Socket socket, URI uri, boolean close) throws Exception {
        Http1Client http = new Http1Client(socket, socket.getInputStream(), socket.getOutputStream(), uri);
        http.writeRequestLine(Method.GET, "/").writeHeader("Connection", close ? "close" : "keep-alive").endHeaders().flush();
        assertEquals("HTTP/1.1 200 OK", http.readLine());
        assertEquals("ok", http.readBody(http.readHeaders()));
    }
}
