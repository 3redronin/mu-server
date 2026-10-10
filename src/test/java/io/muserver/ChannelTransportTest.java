package io.muserver;

import okhttp3.Protocol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.*;
import static scaffolding.ClientUtils.*;

@Timeout(30)
class ChannelTransportTest {
    private static MuServerBuilder builder(boolean secure, boolean h2) {
        MuServerBuilder builder = MuServerBuilder.muServer().withThreadingMode(ThreadingMode.PLATFORM)
            .withHttp2Config(h2 ? Http2ConfigBuilder.http2Enabled() : Http2ConfigBuilder.http2Disabled());
        if (secure) builder.withHttpsPort(0); else builder.withHttpPort(0);
        builder.useChannelTransport = true;
        return builder;
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void http1PipelineBodyHalfCloseAndPlaintextAccounting(boolean sniffH2) throws Exception {
        try (MuServer server = builder(false, sniffH2).addHandler((request, response) -> {
            response.write(request.uri().getPath() + ":" + request.readBodyAsString());
            return true;
        }).start(); Socket socket = connect(server)) {
            String requests = "POST /first HTTP/1.1\r\nHost: localhost\r\nContent-Length: 5\r\n\r\nhello"
                + "GET /second HTTP/1.1\r\nHost: localhost\r\n\r\n";
            socket.getOutputStream().write(requests.getBytes(US_ASCII));
            socket.shutdownOutput();
            String result = new String(socket.getInputStream().readAllBytes(), US_ASCII);
            assertEquals(2, result.split("HTTP/1.1 200 OK", -1).length - 1, result);
            assertTrue(result.contains("/first:hello"), result);
            assertTrue(result.contains("/second:"), result);
            until(() -> server.stats().completedConnections() == 1);
            assertEquals(requests.length(), server.stats().bytesRead());
            assertEquals(result.length(), server.stats().bytesSent());
            assertEquals(2, server.stats().completedRequests());
            assertEquals(0, server.stats().activeConnections());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"TLSv1.2", "TLSv1.3"})
    void tlsLargeDuplexTransferSniAndCloseNotify(String protocol) throws Exception {
        String body = "abcdefgh".repeat(20000);
        CompletableFuture<HttpConnection> handled = new CompletableFuture<>();
        try (MuServer server = builder(true, true).addHandler((request, response) -> {
            handled.complete(request.connection());
            response.write(request.readBodyAsString());
            return true;
        }).start(); SSLSocket socket = (SSLSocket) sslContextForTesting(veryTrustingTrustManager())
            .getSocketFactory().createSocket("localhost", server.uri().getPort())) {
            socket.setSoTimeout(10000);
            socket.setEnabledProtocols(new String[]{protocol});
            var params = socket.getSSLParameters();
            params.setServerNames(List.of(new SNIHostName("localhost")));
            params.setApplicationProtocols(new String[]{"http/1.1"});
            socket.setSSLParameters(params);
            socket.startHandshake();
            String request = "POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: " + body.length()
                + "\r\nConnection: close\r\n\r\n" + body;
            socket.getOutputStream().write(request.getBytes(US_ASCII));
            String result = new String(socket.getInputStream().readAllBytes(), US_ASCII);
            assertTrue(result.startsWith("HTTP/1.1 200 OK"), result.substring(0, Math.min(result.length(), 200)));
            assertTrue(result.contains(body), "The entire response body must survive TLS fragmentation");
            assertEquals("localhost", handled.get().sniHostName().orElse(null));
            assertEquals(protocol, handled.get().httpsProtocol());
            until(() -> server.stats().completedConnections() == 1);
            assertEquals(request.length(), server.stats().bytesRead());
            assertEquals(result.length(), server.stats().bytesSent());
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void http2RunsThroughChannelTransportWithAlpnOrCleartextPreface(boolean secure) throws Exception {
        var client = scaffolding.ClientUtils.client.newBuilder().dispatcher(new okhttp3.Dispatcher())
            .connectionPool(new okhttp3.ConnectionPool()).protocols(secure
            ? List.of(Protocol.HTTP_2, Protocol.HTTP_1_1) : List.of(Protocol.H2_PRIOR_KNOWLEDGE)).build();
        try (MuServer server = builder(secure, true).addHandler((req, res) -> {
            res.write("hello over h2"); return true;
        }).start()) {
            try (var response = call(client, request(server.uri()))) {
                assertEquals(secure ? Protocol.HTTP_2 : Protocol.H2_PRIOR_KNOWLEDGE, response.protocol());
                assertEquals("hello over h2", response.body().string());
            }
        } finally { client.connectionPool().evictAll(); client.dispatcher().executorService().shutdown(); }
    }

    @ParameterizedTest @CsvSource({"1,false", "1,true", "2,false", "2,true"})
    void proxyPreamblePreservesHttpOrTlsBytes(int version, boolean secure) throws Exception {
        byte[] preamble = version == 1 ? "PROXY TCP4 192.0.2.1 192.0.2.2 1234 80\r\n".getBytes(US_ASCII)
            : ByteBuffer.allocate(28).put("\r\n\r\n\0\r\nQUIT\n".getBytes(US_ASCII))
                .put((byte) 0x21).put((byte) 0x11).putShort((short) 12)
                .put(new byte[]{(byte) 192, 0, 2, 1, (byte) 192, 0, 2, 2})
                .putShort((short) 1234).putShort((short) 80).array();
        try (MuServer server = builder(secure, true)
            .withHAProxyProtocolConfig(HAProxyProtocolConfigBuilder.config())
            .addHandler((req, res) -> { res.write(req.connection().proxyInfo().orElseThrow().sourceAddress()); return true; }).start();
             Socket raw = socketWithPrefix(server, preamble)) {
            String request = "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
            if (!secure) raw.getOutputStream().write(request.getBytes(US_ASCII));
            Socket application = raw;
            if (secure) {
                application = sslContextForTesting(veryTrustingTrustManager()).getSocketFactory()
                    .createSocket(raw, "localhost", server.uri().getPort(), false);
                application.setSoTimeout(10000);
                application.getOutputStream().write(request.getBytes(US_ASCII));
            }
            try {
                String result = new String(application.getInputStream().readAllBytes(), US_ASCII);
                assertTrue(result.startsWith("HTTP/1.1 200 OK"), result);
                assertTrue(result.contains("192.0.2.1"), result);
                until(() -> server.stats().completedConnections() == 1);
                assertEquals(request.length(), server.stats().bytesRead());
            } finally { if (application != raw) application.close(); }
        }
    }

    @Test
    void idleAndSuspendedHttp1ConnectionsLeaveApplicationWorkerAvailable() throws Exception {
        var application = Executors.newSingleThreadExecutor();
        var internal = Executors.newSingleThreadExecutor();
        var timer = Executors.newSingleThreadScheduledExecutor();
        Queue<AsyncHandle> suspended = new ConcurrentLinkedQueue<>();
        List<Socket> clients = new ArrayList<>();
        MuServerBuilder builder = builder(false, false).withHandlerExecutor(application);
        builder.executionResourcesFactory = (supplied, mode) -> new ExecutionResources(application, false, internal, timer);
        try (MuServer server = builder.addHandler((req, res) -> {
            suspended.add(req.handleAsync()); return true;
        }).start()) {
            try {
                for (int i = 0; i < 128; i++) clients.add(connect(server));
                until(() -> server.stats().activeConnections() == 128);
                application.submit(() -> {}).get(2, TimeUnit.SECONDS);
                internal.submit(() -> {}).get(2, TimeUnit.SECONDS);
                for (Socket client : clients) client.getOutputStream().write(
                    "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".getBytes(US_ASCII));
                until(() -> suspended.size() == 128);
                application.submit(() -> {}).get(2, TimeUnit.SECONDS);
                internal.submit(() -> {}).get(2, TimeUnit.SECONDS);
                AsyncHandle handle;
                while ((handle = suspended.poll()) != null) {
                    handle.write(ByteBuffer.wrap("ok".getBytes(US_ASCII)));
                    handle.complete();
                }
                for (Socket client : clients) assertTrue(new String(client.getInputStream().readAllBytes(), US_ASCII).contains("ok"));
                until(() -> server.stats().completedConnections() == 128);
                assertTrue(server.stats().activeRequests().isEmpty());
            } finally { for (Socket socket : clients) socket.close(); }
        } finally { application.shutdownNow(); internal.shutdownNow(); timer.shutdownNow(); }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void websocketUpgradeAndCallbacksUseTheChannelTransport(boolean secure) throws Exception {
        CompletableFuture<String> received = new CompletableFuture<>();
        CompletableFuture<Void> closed = new CompletableFuture<>();
        try (MuServer server = builder(secure, true).addHandler(WebSocketHandlerBuilder.webSocketHandler((req, headers) ->
            new SimpleWebSocket() {
                @Override public void onText(String message) throws IOException { session().sendText(message); }
                @Override public void onBinary(ByteBuffer message) { }
            }).withPingInterval(0, TimeUnit.SECONDS)).start()) {
            var websocket = client.newWebSocket(request(server.uri()).build(), new okhttp3.WebSocketListener() {
                @Override public void onOpen(okhttp3.WebSocket socket, okhttp3.Response response) { socket.send("hello"); }
                @Override public void onMessage(okhttp3.WebSocket socket, String text) {
                    received.complete(text); socket.close(1000, "done");
                }
                @Override public void onClosed(okhttp3.WebSocket socket, int code, String reason) { closed.complete(null); }
                @Override public void onFailure(okhttp3.WebSocket socket, Throwable error, okhttp3.Response response) {
                    received.completeExceptionally(error); closed.completeExceptionally(error);
                }
            });
            try {
                assertEquals("hello", received.get(5, TimeUnit.SECONDS));
                closed.get(5, TimeUnit.SECONDS);
                until(() -> server.stats().completedConnections() == 1);
            } finally { websocket.cancel(); }
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void abruptWebsocketDisconnectDeliversTheErrorCallback(boolean secure) throws Exception {
        CompletableFuture<Void> connected = new CompletableFuture<>();
        CompletableFuture<Throwable> error = new CompletableFuture<>();
        try (MuServer server = builder(secure, false).addHandler(WebSocketHandlerBuilder.webSocketHandler((req, headers) ->
            new BaseWebSocket() {
                @Override public void onConnect(MuWebSocketSession session) throws Exception {
                    super.onConnect(session); connected.complete(null);
                }
                @Override public void onError(Throwable cause) throws Exception {
                    super.onError(cause); error.complete(cause);
                }
            }).withPingInterval(0, TimeUnit.SECONDS)).start()) {
            var websocket = client.newWebSocket(request(server.uri()).build(), new okhttp3.WebSocketListener() {});
            try {
                connected.get(5, TimeUnit.SECONDS);
                websocket.cancel();
                assertInstanceOf(ClientDisconnectedException.class, error.get(5, TimeUnit.SECONDS));
                until(() -> server.stats().completedConnections() == 1);
            } finally { websocket.cancel(); }
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void forcedStopCancelsSuspendedAsyncAndRetiresItsAccounting(boolean secure) throws Exception {
        CompletableFuture<AsyncHandle> suspended = new CompletableFuture<>();
        CompletableFuture<ResponseInfo> completion = new CompletableFuture<>();
        try (MuServer server = builder(secure, false).addHandler((req, res) -> {
            res.addCompletionListener(completion::complete);
            suspended.complete(req.handleAsync()); return true;
        }).start(); Socket socket = applicationSocket(server, secure)) {
            socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(US_ASCII));
            suspended.get(5, TimeUnit.SECONDS);
            assertFalse(server.stop(0, TimeUnit.MILLISECONDS));
            assertFalse(completion.get(5, TimeUnit.SECONDS).completedSuccessfully());
            until(() -> server.stats().completedConnections() == 1);
            assertTrue(server.stats().activeRequests().isEmpty());
            assertEquals(0, server.stats().activeConnections());
            assertEquals(1, server.stats().completedRequests());
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void stalledResponseDoesNotPreventAnotherConnectionAndAbortReleasesWriter(boolean secure) throws Exception {
        CompletableFuture<HttpConnection> writing = new CompletableFuture<>();
        CompletableFuture<ResponseInfo> completion = new CompletableFuture<>();
        try (MuServer server = builder(secure, false).addHandler((req, res) -> {
            if (req.uri().getPath().equals("/slow")) {
                ((ChannelConnection) ((BaseHttpConnection) req.connection()).transport).socket.setSendBufferSize(4096);
                res.addCompletionListener(completion::complete);
                writing.complete(req.connection());
                res.contentType("application/octet-stream");
                res.outputStream().write(new byte[8 * 1024 * 1024]);
            } else res.write("healthy");
            return true;
        }).start(); Socket slow = applicationSocket(server, secure)) {
            slow.setReceiveBufferSize(4096);
            slow.getOutputStream().write("GET /slow HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(US_ASCII));
            HttpConnection blocked = writing.get(5, TimeUnit.SECONDS);
            try (var response = call(request(server.uri()))) { assertEquals("healthy", response.body().string()); }
            assertFalse(completion.isDone(), "The peer has not drained the large response");
            blocked.abort();
            assertFalse(completion.get(5, TimeUnit.SECONDS).completedSuccessfully());
            until(() -> server.stats().completedRequests() == 2);
        }
    }

    @Test
    void partialProxyPreambleExpiresAndReleasesTheOnlyConnectionSlot() throws Exception {
        try (MuServer server = builder(false, true).withMaxConnections(1)
            .withHAProxyProtocolConfig(HAProxyProtocolConfigBuilder.config().withTimeout(200, TimeUnit.MILLISECONDS))
            .addHandler((req, res) -> { res.write("ok"); return true; }).start(); Socket slow = connect(server)) {
            slow.getOutputStream().write("PROXY TCP4 ".getBytes(US_ASCII));
            assertEquals(-1, slow.getInputStream().read());
            until(() -> server.stats().failedToConnect() == 1);
            try (Socket healthy = connect(server)) {
                healthy.getOutputStream().write(("PROXY UNKNOWN\r\nGET / HTTP/1.1\r\nHost: localhost\r\n"
                    + "Connection: close\r\n\r\n").getBytes(US_ASCII));
                assertTrue(new String(healthy.getInputStream().readAllBytes(), US_ASCII).contains("ok"));
            }
            until(() -> server.stats().completedConnections() == 1);
            assertEquals(1, server.stats().failedToConnect());
        }
    }

    @Test
    void blockedTlsTrustTaskDoesNotBlockOtherClientsOrForcedShutdown() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var taskExited = new CountDownLatch(1);
        var worker = Executors.newSingleThreadExecutor();
        var trust = new javax.net.ssl.X509TrustManager() {
            @Override public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                entered.countDown();
                boolean interrupted = false;
                try {
                    for (;;) {
                        try { release.await(); break; }
                        catch (InterruptedException ignored) { interrupted = true; }
                    }
                } finally {
                    taskExited.countDown();
                    if (interrupted) Thread.currentThread().interrupt();
                }
            }
            @Override public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) { }
            @Override public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
        };
        try (MuServer server = builder(true, false).withHttpsConfig(HttpsConfigBuilder.unsignedLocalhost()
            .withClientCertificateTrustManager(trust)).addHandler((req, res) -> { res.write("healthy"); return true; }).start();
             SSLSocket socket = (SSLSocket) getPKCS12Context("/client-certs/client.p12", "export password")
                 .getSocketFactory().createSocket("localhost", server.uri().getPort())) {
            socket.setSoTimeout(10000);
            var handshake = worker.submit(() -> {
                try {
                    socket.startHandshake();
                    return socket.getInputStream().read();
                } catch (IOException expected) { return -1; }
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            try (var response = call(request(server.uri()))) { assertEquals("healthy", response.body().string()); }
            long started = System.nanoTime();
            server.stop(0, TimeUnit.MILLISECONDS);
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2), "Shutdown waited for the trust manager");
            assertEquals(-1, handshake.get(5, TimeUnit.SECONDS));
            release.countDown();
            assertTrue(taskExited.await(5, TimeUnit.SECONDS));
            until(() -> server.stats().activeConnections() == 0);
        } finally { release.countDown(); worker.shutdownNow(); }
    }

    @Test
    void tlsTaskRejectionFailsTheHandshakeAndCountsOverload() throws Exception {
        CompletableFuture<HttpConnection> handled = new CompletableFuture<>();
        try (MuServer server = builder(true, false).addHandler((req, res) -> {
            handled.complete(req.connection()); res.write("ok"); return true;
        }).start()) {
            try (Socket initial = applicationSocket(server, true)) {
                initial.getOutputStream().write("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".getBytes(US_ASCII));
                assertTrue(new String(initial.getInputStream().readAllBytes(), US_ASCII).contains("ok"));
            }
            var loopField = ChannelConnection.class.getDeclaredField("loop");
            loopField.setAccessible(true);
            ChannelTransportLoop loop = (ChannelTransportLoop) loopField.get(((BaseHttpConnection) handled.get()).transport);
            loop.tlsTasks.shutdown(); // Deterministically reject the next engine's delegated batch.
            try (SSLSocket rejected = (SSLSocket) applicationSocket(server, true)) {
                try {
                    rejected.startHandshake();
                    // JSSE may accept an early close_notify without throwing from startHandshake.
                    // It must expose EOF without establishing an application connection.
                    assertEquals(-1, rejected.getInputStream().read());
                } catch (java.net.SocketTimeoutException timeout) { throw timeout; }
                catch (IOException expected) { }
            }
            until(() -> server.stats().failedToConnect() == 1);
            assertEquals(1, server.stats().rejectedDueToOverload());
            until(() -> server.stats().activeConnections() == 0);
        }
    }

    @Test
    void stalledTlsSetupExpiresAndReleasesAdmission() throws Exception {
        try (MuServer server = builder(true, false).withMaxConnections(1)
            .addHandler((req, res) -> { res.write("healthy"); return true; }).start(); Socket stalled = connect(server)) {
            stalled.setSoTimeout(15000);
            // An incomplete TLS record must not reserve the connection slot indefinitely.
            stalled.getOutputStream().write(new byte[]{22, 3, 3, 0, 42, 1});
            assertEquals(-1, stalled.getInputStream().read());
            until(() -> server.stats().failedToConnect() == 1);
            try (var response = call(request(server.uri()))) { assertEquals("healthy", response.body().string()); }
        }
    }

    @Test
    void http2InputFailureCancelsAnAsyncExchangeAndRetiresIt() throws Exception {
        CompletableFuture<Http2Connection> handled = new CompletableFuture<>();
        CompletableFuture<ResponseInfo> completed = new CompletableFuture<>();
        try (MuServer server = builder(true, true).addHandler((req, res) -> {
            res.addCompletionListener(completed::complete);
            req.handleAsync();
            handled.complete((Http2Connection) req.connection());
            return true;
        }).start(); H2Client client = new H2Client(); var peer = client.connect(server)) {
            peer.handshake().writeFrame(new Http2HeadersFrame(1, true,
                RFCTestUtils.getHelloHeaders("https", server.uri().getPort()))).flush();
            Http2Connection connection = handled.get(5, TimeUnit.SECONDS);
            // Inject the failure at the plaintext bridge to make the reader observe it before
            // any separate socket-abort notification can cancel the exchange for it.
            var inputField = ChannelConnection.class.getDeclaredField("input");
            inputField.setAccessible(true);
            TransportInputBuffer input = (TransportInputBuffer) inputField.get(connection.transport);
            input.fail(new javax.net.ssl.SSLException("TLS input failed"));
            assertFalse(completed.get(5, TimeUnit.SECONDS).completedSuccessfully());
            until(() -> server.stats().completedConnections() == 1);
            assertTrue(server.stats().activeRequests().isEmpty());
            assertEquals(1, server.stats().completedRequests());
        }
    }

    @Test
    void forcedStopDoesNotSpinWhileAnApplicationHandlerRemainsActive() throws Exception {
        var cpu = java.lang.management.ManagementFactory.getThreadMXBean();
        org.junit.jupiter.api.Assumptions.assumeTrue(cpu.isThreadCpuTimeSupported());
        if (!cpu.isThreadCpuTimeEnabled()) cpu.setThreadCpuTimeEnabled(true);
        CompletableFuture<HttpConnection> handled = new CompletableFuture<>();
        CompletableFuture<ResponseInfo> completed = new CompletableFuture<>();
        var release = new CountDownLatch(1);
        try (MuServer server = builder(false, false).addHandler((req, res) -> {
            res.addCompletionListener(completed::complete);
            handled.complete(req.connection());
            release.await(); return true;
        }).start(); Socket socket = connect(server)) {
            socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(US_ASCII));
            BaseHttpConnection connection = (BaseHttpConnection) handled.get(5, TimeUnit.SECONDS);
            var loopField = ChannelConnection.class.getDeclaredField("loop");
            loopField.setAccessible(true);
            var threadField = ChannelTransportLoop.class.getDeclaredField("thread");
            threadField.setAccessible(true);
            Thread owner = (Thread) threadField.get(loopField.get(connection.transport));
            assertFalse(server.stop(0, TimeUnit.MILLISECONDS));
            assertEquals(-1, socket.getInputStream().read());
            long before = cpu.getThreadCpuTime(owner.getId());
            // A retained handler deliberately keeps protocol completion pending through this window.
            Thread.sleep(300);
            long after = cpu.getThreadCpuTime(owner.getId());
            assertTrue(before >= 0 && after >= before, "The readiness owner must still account for the active handler");
            assertTrue(after - before < TimeUnit.MILLISECONDS.toNanos(100), "Readiness owner spun after forced stop");
            assertFalse(completed.isDone());
            assertEquals(1, server.stats().activeRequests().size());
            release.countDown();
            assertFalse(completed.get(5, TimeUnit.SECONDS).completedSuccessfully());
            until(() -> server.stats().completedConnections() == 1);
            owner.join(2000);
            assertFalse(owner.isAlive());
        } finally { release.countDown(); }
    }

    @Test
    void unexpectedSelectorFailurePreservesAnActiveHandlersLifetime() throws Exception {
        CompletableFuture<HttpConnection> handled = new CompletableFuture<>();
        CompletableFuture<ResponseInfo> completed = new CompletableFuture<>();
        var release = new CountDownLatch(1);
        try (MuServer server = builder(false, false).addHandler((req, res) -> {
            res.addCompletionListener(completed::complete);
            handled.complete(req.connection());
            release.await();
            return true;
        }).start(); Socket socket = connect(server)) {
            socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(US_ASCII));
            BaseHttpConnection connection = (BaseHttpConnection) handled.get(5, TimeUnit.SECONDS);
            // Fault injection: invalidate the owner's selector while application code is active.
            var loopField = ChannelConnection.class.getDeclaredField("loop");
            loopField.setAccessible(true);
            ChannelTransportLoop loop = (ChannelTransportLoop) loopField.get(connection.transport);
            loop.selector.close();
            assertEquals(-1, socket.getInputStream().read());
            assertFalse(completed.isDone());
            assertEquals(1, server.stats().activeConnections());
            assertEquals(1, server.stats().activeRequests().size());
            release.countDown();
            assertFalse(completed.get(5, TimeUnit.SECONDS).completedSuccessfully());
            until(() -> server.stats().completedConnections() == 1);
            assertTrue(server.stats().activeRequests().isEmpty());
        } finally { release.countDown(); }
    }

    /** Inject the preamble in the very same write as the first HTTP bytes or TLS ClientHello. */
    private static Socket socketWithPrefix(MuServer server, byte[] prefix) throws IOException {
        Socket socket = new Socket("localhost", server.uri().getPort()) {
            boolean first = true;
            @Override public java.io.OutputStream getOutputStream() throws IOException {
                return new java.io.FilterOutputStream(super.getOutputStream()) {
                    @Override public void write(int value) throws IOException { write(new byte[]{(byte) value}); }
                    @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                        if (first) {
                            first = false;
                            byte[] combined = ByteBuffer.allocate(prefix.length + length).put(prefix).put(bytes, offset, length).array();
                            out.write(combined);
                        } else out.write(bytes, offset, length);
                    }
                };
            }
        };
        socket.setSoTimeout(10000);
        return socket;
    }

    private static Socket applicationSocket(MuServer server, boolean secure) throws IOException {
        Socket socket = secure ? sslContextForTesting(veryTrustingTrustManager()).getSocketFactory()
            .createSocket("localhost", server.uri().getPort()) : connect(server);
        socket.setSoTimeout(10000);
        return socket;
    }

    private static Socket connect(MuServer server) throws IOException {
        Socket socket = new Socket("localhost", server.uri().getPort());
        socket.setSoTimeout(10000);
        return socket;
    }

    private static void until(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "Condition did not become true");
            Thread.sleep(2);
        }
    }
}
