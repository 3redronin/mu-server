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
    void idleWebsocketsPartialFramesAndAsyncReceivesLeaveBothWorkersAvailable(boolean secure) throws Exception {
        var application = Executors.newSingleThreadExecutor();
        var internal = Executors.newSingleThreadExecutor();
        var timer = Executors.newSingleThreadScheduledExecutor();
        var connected = new java.util.concurrent.atomic.AtomicInteger();
        Queue<DoneCallback> callbacks = new ConcurrentLinkedQueue<>();
        List<WebSocketWireTestSupport> clients = new ArrayList<>();
        MuServerBuilder builder = builder(secure, false).withHandlerExecutor(application);
        builder.executionResourcesFactory = (supplied, mode) -> new ExecutionResources(application, false, internal, timer);
        var ssl = sslContextForTesting(veryTrustingTrustManager());
        try (MuServer server = builder.addHandler(WebSocketHandlerBuilder.webSocketHandler((req, headers) -> new BaseWebSocket() {
            @Override public void onConnect(MuWebSocketSession session) throws Exception {
                super.onConnect(session);
                connected.incrementAndGet();
            }
            @Override public void onText(String text, boolean last, DoneCallback done) { callbacks.add(done); }
        }).withPingInterval(0, TimeUnit.MILLISECONDS)).start()) {
            try {
                for (int i = 0; i < 128; i++) {
                    Socket socket = secure ? ssl.getSocketFactory().createSocket("localhost", server.uri().getPort()) : connect(server);
                    clients.add(new WebSocketWireTestSupport(server, socket, Integer.MAX_VALUE));
                }
                until(() -> connected.get() == 128);
                application.submit(() -> {}).get(2, TimeUnit.SECONDS);
                internal.submit(() -> {}).get(2, TimeUnit.SECONDS);
                byte[] frame = WebSocketWireTestSupport.frame(true, 1, "hold".getBytes(US_ASCII));
                long before = server.stats().bytesRead();
                for (var client : clients) { client.output.write(frame, 0, 1); client.output.flush(); }
                until(() -> server.stats().bytesRead() == before + 128);
                internal.submit(() -> {}).get(2, TimeUnit.SECONDS);
                for (var client : clients) {
                    client.output.write(frame, 1, frame.length - 1);
                    client.send(true, 9, new byte[]{4});
                }
                until(() -> callbacks.size() == 128);
                application.submit(() -> {}).get(2, TimeUnit.SECONDS);
                internal.submit(() -> {}).get(2, TimeUnit.SECONDS);
                DoneCallback callback;
                while ((callback = callbacks.poll()) != null) callback.onComplete(null);
                for (var client : clients) assertArrayEquals(new byte[]{4}, client.readFrame(10));
                for (var client : clients) client.send(true, 8, new byte[]{3, (byte) 232});
                for (var client : clients) assertArrayEquals(new byte[]{3, (byte) 232}, client.readFrame(8));
                until(() -> server.stats().activeConnections() == 0);
                assertEquals(128, server.stats().completedConnections());
            } finally {
                DoneCallback callback;
                while ((callback = callbacks.poll()) != null) callback.onComplete(null);
                for (var client : clients) client.close();
            }
        } finally {
            application.shutdownNow();
            internal.shutdownNow();
            timer.shutdownNow();
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void websocketInputHalfCloseBoundsStalledOutputEvenAfterProtocolRetirement(boolean secure) throws Exception {
        var transport = new CompletableFuture<ChannelConnection>();
        var session = new CompletableFuture<MuWebSocketSession>();
        var completion = new CompletableFuture<Throwable>();
        MuServerBuilder builder = builder(secure, false).withIdleTimeout(500, TimeUnit.MILLISECONDS);
        builder.addHandler((req, res) -> {
            var connection = (ChannelConnection) ((BaseHttpConnection) req.connection()).transport;
            connection.socket.setSendBufferSize(4096);
            transport.complete(connection);
            return false;
        });
        builder.addHandler(WebSocketHandlerBuilder.webSocketHandler((req, headers) -> new SimpleWebSocket() {
            @Override public void onConnect(MuWebSocketSession connected) throws Exception {
                super.onConnect(connected);
                session.complete(connected);
            }
            @Override public void onText(String text) {
                session().sendBinary(ByteBuffer.wrap(new byte[8 * 1024 * 1024]), completion::complete);
            }
            @Override public void onBinary(ByteBuffer bytes) { }
        }).withPingInterval(0, TimeUnit.MILLISECONDS).withIdleReadTimeout(0, TimeUnit.MILLISECONDS));
        try (MuServer server = builder.start()) {
            Socket socket = secure ? sslContextForTesting(veryTrustingTrustManager()).getSocketFactory()
                .createSocket("localhost", server.uri().getPort()) : connect(server);
            if (secure) ((SSLSocket) socket).setEnabledProtocols(new String[]{"TLSv1.3"});
            try (var client = new WebSocketWireTestSupport(server, socket, Integer.MAX_VALUE)) {
                client.socket.setReceiveBufferSize(4096);
                client.send(true, 1, "go".getBytes(US_ASCII));
                var field = ChannelConnection.class.getDeclaredField("output");
                field.setAccessible(true);
                var output = (TransportOutputBuffer) field.get(transport.get(2, TimeUnit.SECONDS));
                until(() -> output.pendingBytes() > 0);
                assertFalse(completion.isDone());
                client.socket.shutdownOutput(); // EOF / TLS close_notify, with the response direction still stalled.
                until(() -> server.stats().activeConnections() == 0);
                assertNotNull(completion.get(2, TimeUnit.SECONDS));
                assertTrue(session.get().state().endState());
                assertEquals(1, server.stats().completedConnections());
                assertEquals(0, output.pendingBytes());
            }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"plain", "TLSv1.2", "TLSv1.3"})
    void stalledAsyncWebsocketSendsAndPingsLeaveTheInternalWorkerAvailable(String protocol) throws Exception {
        boolean secure = !protocol.equals("plain");
        var application = Executors.newSingleThreadExecutor();
        var internal = Executors.newSingleThreadExecutor();
        var timer = Executors.newSingleThreadScheduledExecutor();
        Queue<ChannelConnection> stalled = new ConcurrentLinkedQueue<>();
        Queue<Throwable> failures = new ConcurrentLinkedQueue<>();
        List<WebSocketWireTestSupport> peers = new ArrayList<>();
        byte[] payload = new byte[8 * 1024 * 1024];
        MuServerBuilder builder = builder(secure, false).withHandlerExecutor(application);
        builder.executionResourcesFactory = (supplied, mode) -> new ExecutionResources(application, false, internal, timer);
        builder.addHandler((req, res) -> {
            if (req.headers().get("Upgrade") == null) { res.write("healthy"); return true; }
            var transport = (ChannelConnection) ((BaseHttpConnection) req.connection()).transport;
            transport.socket.setSendBufferSize(4096);
            stalled.add(transport);
            return false;
        }).addHandler(WebSocketHandlerBuilder.webSocketHandler((req, headers) -> new BaseWebSocket() {
            @Override public void onText(String text, boolean last, DoneCallback done) {
                session().sendBinary(ByteBuffer.wrap(payload).asReadOnlyBuffer(), error -> {
                    assertNotNull(error);
                    failures.add(error);
                    done.onComplete(error);
                });
            }
        }).withPingInterval(20, TimeUnit.MILLISECONDS));
        try (MuServer server = builder.start()) {
            try {
                for (int i = 0; i < 3; i++) {
                    Socket socket = secure ? sslContextForTesting(veryTrustingTrustManager()).getSocketFactory()
                        .createSocket("localhost", server.uri().getPort()) : connect(server);
                    if (secure) ((SSLSocket) socket).setEnabledProtocols(new String[]{protocol});
                    var peer = new WebSocketWireTestSupport(server, socket, Integer.MAX_VALUE);
                    peers.add(peer);
                    peer.socket.setReceiveBufferSize(4096);
                    peer.send(true, 1, "go".getBytes(US_ASCII));
                }
                until(() -> stalled.size() == 3);
                var field = ChannelConnection.class.getDeclaredField("output");
                field.setAccessible(true);
                List<TransportOutputBuffer> outputs = new ArrayList<>();
                for (var connection : stalled) outputs.add((TransportOutputBuffer) field.get(connection));
                until(() -> outputs.stream().allMatch(output -> output.pendingBytes() > 0));
                // Let the automatic ping timers also enqueue behind the stalled data frames.
                timer.schedule(() -> {}, 100, TimeUnit.MILLISECONDS).get(2, TimeUnit.SECONDS);
                internal.submit(() -> {}).get(2, TimeUnit.SECONDS);
                application.submit(() -> {}).get(2, TimeUnit.SECONDS);
                try (var response = call(request(server.uri()).header("Connection", "close"))) {
                    assertEquals("healthy", response.body().string());
                }
                assertTrue(failures.isEmpty());
                for (var connection : stalled) connection.abort();
                until(() -> failures.size() == 3);
                until(() -> server.stats().activeConnections() == 0);
                assertEquals(4, server.stats().completedConnections());
            } finally { for (var peer : peers) peer.close(); }
        } finally { application.shutdownNow(); internal.shutdownNow(); timer.shutdownNow(); }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void inlineWriteCallbackCanPerformAnotherBlockingWebsocketSend(boolean secure) throws Exception {
        var application = new java.util.concurrent.ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new java.util.concurrent.SynchronousQueue<>(), new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        var occupied = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        application.execute(() -> {
            occupied.countDown();
            try { release.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        assertTrue(occupied.await(2, TimeUnit.SECONDS));
        var completed = new CompletableFuture<Void>();
        try (MuServer server = builder(secure, false).withHandlerExecutor(application)
            .addHandler(WebSocketHandlerBuilder.webSocketHandler((req, headers) -> new BaseWebSocket() {
                @Override public void onText(String text, boolean last, DoneCallback done) {
                    session().sendText("first", error -> {
                        try {
                            if (error != null) throw new IOException("Initial write failed", error);
                            session().sendText("nested");
                            done.onComplete(null);
                            completed.complete(null);
                        } catch (Throwable failure) { done.onComplete(failure); completed.completeExceptionally(failure); }
                    });
                }
            }).withPingInterval(0, TimeUnit.MILLISECONDS)).start()) {
            Socket socket = secure ? sslContextForTesting(veryTrustingTrustManager()).getSocketFactory()
                .createSocket("localhost", server.uri().getPort()) : connect(server);
            try (var peer = new WebSocketWireTestSupport(server, socket, Integer.MAX_VALUE)) {
                peer.send(true, 1, "go".getBytes(US_ASCII));
                assertArrayEquals("first".getBytes(US_ASCII), peer.readFrame(1));
                assertArrayEquals("nested".getBytes(US_ASCII), peer.readFrame(1));
                completed.get(2, TimeUnit.SECONDS);
                peer.send(true, 8, new byte[]{3, (byte) 232});
                peer.readFrame(8);
                until(() -> server.stats().activeConnections() == 0);
            }
        } finally { release.countDown(); application.shutdownNow(); }
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

    @Test
    void unexpectedHttp2HandlerExecutorFailureRetiresTheUndispatchedStream() throws Exception {
        var failNext = new java.util.concurrent.atomic.AtomicBoolean(true);
        var application = new java.util.concurrent.ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new java.util.concurrent.LinkedBlockingQueue<>()) {
            @Override public void execute(Runnable task) {
                if (failNext.compareAndSet(true, false)) throw new IllegalStateException("Injected executor failure");
                super.execute(task);
            }
        };
        try (MuServer server = builder(false, true).withHandlerExecutor(application)
            .addHandler((req, res) -> { res.status(204); return true; }).start();
             H2Client client = new H2Client(); var peer = client.connectClearText(server)) {
            FieldBlock headers = RFCTestUtils.getHelloHeaders("http", server.uri().getPort());
            peer.handshake().writeFrame(new Http2HeadersFrame(1, true, headers)).flush();
            var reset = RFCTestUtils.readIgnoringWindowUpdates(peer, Http2ResetStreamFrame.class);
            assertEquals(1, reset.streamId());
            assertEquals(Http2ErrorCode.INTERNAL_ERROR.code(), reset.errorCode());
            until(() -> server.stats().completedRequests() == 1 && server.stats().activeRequests().isEmpty());
            peer.writeFrame(new Http2HeadersFrame(3, true, headers)).flush();
            assertEquals("204", RFCTestUtils.readIgnoringWindowUpdates(peer, Http2HeadersFrame.class).headers().get(":status"));
            until(() -> server.stats().completedRequests() == 2);
        } finally { application.shutdownNow(); }
    }

    @Test
    void inlineHttp2HandlerSubmissionCannotBlockItsOwnRequestBodyReader() throws Exception {
        CountDownLatch occupied = new CountDownLatch(1), release = new CountDownLatch(1);
        var application = new java.util.concurrent.ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new java.util.concurrent.SynchronousQueue<>(), new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        CompletableFuture<String> entered = new CompletableFuture<>();
        application.execute(() -> {
            occupied.countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        assertTrue(occupied.await(2, TimeUnit.SECONDS));
        try (MuServer server = builder(false, true).withHandlerExecutor(application).addHandler((req, res) -> {
            entered.complete(Thread.currentThread().getName());
            res.write(req.readBodyAsString());
            return true;
        }).start(); H2Client client = new H2Client(); var peer = client.connectClearText(server)) {
            FieldBlock headers = RFCTestUtils.getHelloHeaders("http", server.uri().getPort());
            headers.set(":method", "POST");
            peer.handshake().writeFrame(new Http2HeadersFrame(1, false, headers)).flush();
            assertFalse(entered.get(5, TimeUnit.SECONDS).startsWith("mu-channel-loop-"));
            peer.writeFrame(RFCTestUtils.utf8DataFrame(1, true, "body after handler submission")).flush();
            assertEquals("200", RFCTestUtils.readIgnoringWindowUpdates(peer, Http2HeadersFrame.class).headers().get(":status"));
            var body = RFCTestUtils.readIgnoringWindowUpdates(peer, Http2DataFrame.class);
            assertEquals("body after handler submission", new String(body.payload(), body.payloadOffset(), body.payloadLength(), US_ASCII));
            until(() -> server.stats().completedRequests() == 1);
        } finally { release.countDown(); application.shutdownNow(); }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void idleAndSuspendedHttp2ConnectionsReleaseTheInternalWorker(boolean secure) throws Exception {
        var application = Executors.newSingleThreadExecutor();
        var internal = Executors.newSingleThreadExecutor();
        var timer = Executors.newSingleThreadScheduledExecutor();
        Queue<AsyncHandle> handles = new ConcurrentLinkedQueue<>();
        List<Socket> clients = new ArrayList<>();
        MuServerBuilder builder = builder(secure, true).withHandlerExecutor(application);
        builder.executionResourcesFactory = (supplied, mode) -> new ExecutionResources(application, false, internal, timer);
        var ssl = sslContextForTesting(veryTrustingTrustManager()).getSocketFactory();
        try (MuServer server = builder.addHandler((req, res) -> { handles.add(req.handleAsync()); return true; }).start()) {
            try {
                var preface = new java.io.ByteArrayOutputStream();
                preface.write("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(US_ASCII));
                Http2Settings.DEFAULT_CLIENT_SETTINGS.writeTo(null, preface);
                for (int i = 0; i < 128; i++) {
                    Socket socket;
                    if (secure) {
                        SSLSocket tls = (SSLSocket) ssl.createSocket("localhost", server.uri().getPort());
                        clients.add(tls);
                        var parameters = tls.getSSLParameters();
                        parameters.setApplicationProtocols(new String[]{"h2"});
                        tls.setSSLParameters(parameters);
                        tls.startHandshake();
                        socket = tls;
                    } else { socket = connect(server); clients.add(socket); }
                    socket.setSoTimeout(5000);
                    socket.getOutputStream().write(preface.toByteArray());
                    // Current server SETTINGS has five entries (39 bytes), followed by ACK (9).
                    assertEquals(48, socket.getInputStream().readNBytes(48).length);
                    Http2Settings.ACK.writeTo(null, socket.getOutputStream());
                }
                application.submit(() -> {}).get(2, TimeUnit.SECONDS);
                internal.submit(() -> {}).get(2, TimeUnit.SECONDS);
                byte[] headers = RFCTestUtils.headersFrame(1, true, true, RFCTestUtils.encodeFieldBlock(
                    RFCTestUtils.getHelloHeaders(secure ? "https" : "http", server.uri().getPort())));
                for (Socket socket : clients) socket.getOutputStream().write(headers);
                until(() -> handles.size() == 128);
                application.submit(() -> {}).get(2, TimeUnit.SECONDS);
                internal.submit(() -> {}).get(2, TimeUnit.SECONDS);
                AsyncHandle handle;
                while ((handle = handles.poll()) != null) handle.complete();
                until(() -> server.stats().completedRequests() == 128);
            } finally { for (Socket socket : clients) socket.close(); }
            until(() -> server.stats().completedConnections() == 128);
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

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void stalledHttp2WritesLeaveTheInternalWorkerAvailable(boolean secure) throws Exception {
        var application = Executors.newCachedThreadPool();
        var internal = Executors.newSingleThreadExecutor();
        var timer = Executors.newSingleThreadScheduledExecutor();
        Queue<ChannelConnection> stalled = new ConcurrentLinkedQueue<>();
        Queue<ResponseInfo> completed = new ConcurrentLinkedQueue<>();
        List<H2ClientConnection> peers = new ArrayList<>();
        byte[] body = new byte[8 * 1024 * 1024];
        MuServerBuilder builder = builder(secure, true).withHandlerExecutor(application);
        builder.executionResourcesFactory = (supplied, mode) -> new ExecutionResources(application, false, internal, timer);
        try (MuServer server = builder.addHandler((req, res) -> {
            if (req.uri().getPath().equals("/slow")) {
                var transport = (ChannelConnection) ((BaseHttpConnection) req.connection()).transport;
                transport.socket.setSendBufferSize(4096);
                res.addCompletionListener(completed::add);
                stalled.add(transport);
                res.contentType("application/octet-stream");
                res.outputStream().write(body);
            } else res.write("healthy");
            return true;
        }).start(); H2Client client = new H2Client()) {
            try {
                var settings = new Http2Settings(false, 4096, 100, 16 * 1024 * 1024, 16384, 32768);
                for (int i = 0; i < 3; i++) {
                    var peer = secure ? client.connect(server) : client.connectClearText(server);
                    peers.add(peer);
                    peer.socket().setReceiveBufferSize(4096);
                    FieldBlock headers = RFCTestUtils.getHelloHeaders(secure ? "https" : "http", server.uri().getPort());
                    headers.set(":path", "/slow");
                    peer.handshake(settings).writeFrame(new Http2WindowUpdate(0, 16 * 1024 * 1024 - 65535))
                        .writeFrame(new Http2HeadersFrame(1, true, headers)).flush();
                }
                until(() -> stalled.size() == 3);
                var field = ChannelConnection.class.getDeclaredField("output");
                field.setAccessible(true);
                List<TransportOutputBuffer> outputs = new ArrayList<>();
                for (var connection : stalled) outputs.add((TransportOutputBuffer) field.get(connection));
                until(() -> outputs.stream().allMatch(output -> output.pendingBytes() > 0));
                internal.submit(() -> {}).get(2, TimeUnit.SECONDS);
                try (var healthy = secure ? client.connect(server) : client.connectClearText(server)) {
                    healthy.handshake().writeFrame(new Http2HeadersFrame(1, true,
                        RFCTestUtils.getHelloHeaders(secure ? "https" : "http", server.uri().getPort()))).flush();
                    assertEquals("200", RFCTestUtils.readIgnoringWindowUpdates(healthy, Http2HeadersFrame.class).headers().get(":status"));
                    assertEquals("healthy", RFCTestUtils.readIgnoringWindowUpdates(healthy, Http2DataFrame.class).toUTF8());
                }
                assertTrue(completed.isEmpty(), "Unread responses must still own their completion");
                for (var connection : stalled) connection.abort();
                until(() -> completed.size() == 3);
                for (var info : completed) assertFalse(info.completedSuccessfully());
                until(() -> server.stats().completedRequests() == 4);
            } finally { for (var peer : peers) peer.close(); }
        } finally { application.shutdownNow(); internal.shutdownNow(); timer.shutdownNow(); }
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
