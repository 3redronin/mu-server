package io.muserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import scaffolding.Http1Client;

import javax.net.ssl.SSLSocket;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static scaffolding.ClientUtils.sslContextForTesting;
import static scaffolding.ClientUtils.veryTrustingTrustManager;

class ProxyProtocolTest {
    @Test void leadingZeroesAreRejected() {
        for (String line : new String[]{
            "PROXY TCP4 01.2.3.4 1.2.3.4 1 2\r\n",
            "PROXY TCP4 1.2.3.4 1.2.3.4 01 2\r\n",
            "PROXY TCP6 ::ffff:01.2.3.4 ::1 1 2\r\n"}) {
            assertThrows(IOException.class, () -> ProxyProtocol.parse(new ByteArrayInputStream(line.getBytes(StandardCharsets.US_ASCII))));
        }
    }

    @Test void v1LimitIncludesCrLf() throws Exception {
        String prefix = "PROXY UNKNOWN ";
        for (int size : new int[]{106, 107, 108}) {
            byte[] bytes = (prefix + "x".repeat(size - prefix.length() - 2) + "\r\n").getBytes(StandardCharsets.US_ASCII);
            if (size <= 107) assertNull(ProxyProtocol.parse(new ByteArrayInputStream(bytes)).sourceAddress());
            else assertThrows(IOException.class, () -> ProxyProtocol.parse(new ByteArrayInputStream(bytes)));
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void maximumPayloadUsesBoundedBulkReads(boolean throughSocket) throws Exception {
        byte[] bytes = ByteBuffer.allocate(65552).put(preamble(2))
            .put((byte) 0xee).putShort((short) (65535 - 15)).array();
        bytes[14] = (byte) 255; bytes[15] = (byte) 255; bytes[65551] = 42;
        class Counted extends ByteArrayInputStream {
            int singles, largest;
            Counted() { super(bytes); }
            @Override public synchronized int read() { singles++; return super.read(); }
            @Override public synchronized int read(byte[] b, int off, int len) {
                largest = Math.max(largest, len); return super.read(b, off, len);
            }
            @Override public byte[] readNBytes(int len) throws IOException {
                largest = Math.max(largest, len); return super.readNBytes(len);
            }
        }
        Counted input = new Counted();
        ProxiedConnectionInfo info;
        if (throughSocket) {
            Socket socket = new Socket() {
                @Override public int getSoTimeout() { return 0; }
                @Override public void setSoTimeout(int millis) { }
                @Override public java.io.InputStream getInputStream() { return input; }
            };
            info = ProxyProtocol.read(socket, MonotonicTime.deadlineAfterMillis(10000));
        } else {
            info = ProxyProtocol.parse(input);
        }
        assertEquals("192.0.2.1", info.sourceAddress());
        assertEquals(42, input.read()); assertTrue(input.singles < 32);
        assertTrue(input.largest <= 4096, "largest read: " + input.largest);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void socketTimeoutBeforeOverallDeadlineIsRetried(boolean bulk) throws Exception {
        ByteArrayInputStream bytes = new ByteArrayInputStream(
            ByteBuffer.allocate(preamble(2).length + 1).put(preamble(2)).put((byte) 42).array());
        int[] timeouts = {0};
        Socket socket = new Socket() {
            int timeout = 713;
            @Override public int getSoTimeout() { return timeout; }
            @Override public void setSoTimeout(int millis) { timeout = millis; }
            @Override public java.io.InputStream getInputStream() {
                return new java.io.InputStream() {
                    private void simulateTimeout(boolean bulkRead) throws IOException {
                        if (bulkRead == bulk && timeouts[0] < 2) {
                            assertEquals(Integer.MAX_VALUE, timeout);
                            timeouts[0]++;
                            throw new java.net.SocketTimeoutException("Simulated SO_TIMEOUT ceiling");
                        }
                    }
                    @Override public int read() throws IOException {
                        simulateTimeout(false); return bytes.read();
                    }
                    @Override public int read(byte[] b, int off, int len) throws IOException {
                        simulateTimeout(true); return bytes.read(b, off, len);
                    }
                };
            }
        };
        long timeout = MuServerBuilder.httpServer()
            .withHAProxyProtocolTimeout(30, TimeUnit.DAYS).haProxyProtocolTimeoutMillis();
        assertEquals("192.0.2.1", ProxyProtocol.read(socket, MonotonicTime.deadlineAfterMillis(timeout)).sourceAddress());
        assertEquals(2, timeouts[0]);
        assertEquals(713, socket.getSoTimeout());
        assertEquals(42, bytes.read(), "Application bytes must remain unread");
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void socketTimeoutAtOverallDeadlineIsNotRetried(boolean bulk) throws Exception {
        long deadline = MonotonicTime.deadlineAfterMillis(100);
        java.net.SocketTimeoutException expired = new java.net.SocketTimeoutException("Expired");
        int[] attempts = {0};
        Socket socket = new Socket() {
            int timeout = 713;
            @Override public int getSoTimeout() { return timeout; }
            @Override public void setSoTimeout(int millis) { timeout = millis; }
            @Override public java.io.InputStream getInputStream() {
                ByteArrayInputStream bytes = new ByteArrayInputStream(preamble(2));
                return new java.io.InputStream() {
                    private void expire(boolean bulkRead) throws IOException {
                        if (bulkRead == bulk) {
                            attempts[0]++;
                            long left;
                            while ((left = MonotonicTime.nanosUntil(deadline)) > 0) {
                                java.util.concurrent.locks.LockSupport.parkNanos(left);
                            }
                            throw expired;
                        }
                    }
                    @Override public int read() throws IOException {
                        expire(false); return bytes.read();
                    }
                    @Override public int read(byte[] b, int off, int len) throws IOException {
                        expire(true); return bytes.read(b, off, len);
                    }
                };
            }
        };
        assertSame(expired, assertThrows(java.net.SocketTimeoutException.class,
            () -> ProxyProtocol.read(socket, deadline)));
        assertEquals(1, attempts[0]);
        assertEquals(713, socket.getSoTimeout());
    }

    @ParameterizedTest @CsvSource({"0,false", "0,true", "1,false", "1,true", "2,false", "2,true", "3,false", "3,true"})
    void knownHeaderLengthsUseBulkReadsWithoutReadAhead(int kind, boolean fragmented) throws Exception {
        byte[] header = kind == 0 ? "PROXY UNKNOWN\r\n".getBytes(StandardCharsets.US_ASCII)
            : kind == 1 ? preamble(1) : kind == 2 ? binary(0x20, 0, new byte[0]) : preamble(2);
        byte[] bytes = java.util.Arrays.copyOf(header, header.length + 1);
        bytes[header.length] = 42;
        class Counted extends ByteArrayInputStream {
            int singles, bulks;
            Counted() { super(bytes); }
            @Override public synchronized int read() { singles++; return super.read(); }
            @Override public synchronized int read(byte[] b, int off, int len) {
                bulks++; return super.read(b, off, fragmented ? Math.min(1, len) : len);
            }
        }
        Counted input = new Counted();
        Socket socket = new Socket() {
            @Override public int getSoTimeout() { return 0; }
            @Override public void setSoTimeout(int millis) { }
            @Override public java.io.InputStream getInputStream() { return input; }
        };
        ProxiedConnectionInfo info = ProxyProtocol.read(socket, MonotonicTime.deadlineAfterMillis(3000));
        assertEquals(kind == 0 || kind == 2 ? null : "192.0.2.1", info.sourceAddress());
        assertEquals(kind == 1 ? header.length - 14 : 1, input.singles);
        assertTrue(input.bulks > 0);
        if (!fragmented) assertEquals(kind == 3 ? 2 : 1, input.bulks);
        assertEquals(42, input.read());
        assertEquals(-1, input.read());
    }

    @Test void timeoutConfigurationIsIndependentAndBounded() {
        MuServerBuilder builder = MuServerBuilder.httpServer();
        assertFalse(builder.haProxyProtocolEnabled());
        assertEquals(10000, builder.haProxyProtocolTimeoutMillis());
        for (long value : new long[]{-1, 0, Long.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> builder.withHAProxyProtocolTimeout(value, TimeUnit.SECONDS));
        }
        assertThrows(IllegalArgumentException.class, () -> builder.withHAProxyProtocolTimeout(999, TimeUnit.MICROSECONDS));
        assertEquals(1, builder.withHAProxyProtocolTimeout(1000, TimeUnit.MICROSECONDS).haProxyProtocolTimeoutMillis());
    }

    @Test void queuedPreambleExpiresEvenWhenInternalExecutorAndHttpTimeoutsAreDisabled() throws Exception {
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.CountDownLatch running = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        executor.submit(() -> { running.countDown(); try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
        assertTrue(running.await(3, TimeUnit.SECONDS));
        try (MuServer server = TestExecutionResources.configure(MuServerBuilder.httpServer(), null, null, executor, null)
            .withIdleTimeout(0, TimeUnit.SECONDS).withRequestTimeout(0, TimeUnit.SECONDS)
            .withHAProxyProtocolEnabled(true).withHAProxyProtocolTimeout(100, TimeUnit.MILLISECONDS).start();
             Socket socket = new Socket("localhost", server.uri().getPort())) {
            socket.setSoTimeout(3000);
            assertEquals(-1, socket.getInputStream().read());
            assertEquals(1, server.stats().failedToConnect());
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test void rejectedExecutorNeverParsesOnAcceptor() throws Exception {
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        executor.shutdown();
        try (MuServer server = TestExecutionResources.configure(MuServerBuilder.httpServer(), executor, null, null, null)
            .withHAProxyProtocolEnabled(true).start()) {
            for (int i = 0; i < 3; i++) {
                try (Socket socket = new Socket("localhost", server.uri().getPort())) {
                    socket.setSoTimeout(1000);
                    assertEquals(-1, socket.getInputStream().read());
                }
            }
        }
    }

    @Test void trickleDoesNotExtendDeadlineAndTimeoutIsRestored() throws Exception {
        byte[] header = preamble(2);
        Socket socket = new Socket() {
            int timeout = 713;
            @Override public int getSoTimeout() { return timeout; }
            @Override public void setSoTimeout(int millis) { timeout = millis; }
            @Override public java.io.InputStream getInputStream() {
                return new ByteArrayInputStream(header) {
                    @Override public synchronized int read() {
                        java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
                        return super.read();
                    }
                    @Override public synchronized int read(byte[] b, int off, int len) {
                        java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
                        return super.read(b, off, Math.min(1, len));
                    }
                };
            }
        };
        assertThrows(java.net.SocketTimeoutException.class,
            () -> ProxyProtocol.read(socket, MonotonicTime.deadlineAfterMillis(10)));
        assertEquals(713, socket.getSoTimeout());
        assertEquals("192.0.2.1", ProxyProtocol.read(socket, MonotonicTime.deadlineAfterMillis(3000)).sourceAddress());
        assertEquals(713, socket.getSoTimeout());
    }

    private static byte[] binary(int command, int family, byte[] payload) {
        return ByteBuffer.allocate(16 + payload.length)
            .put("\r\n\r\n\0\r\nQUIT\n".getBytes(StandardCharsets.US_ASCII))
            .put((byte) command).put((byte) family).putShort((short) payload.length).put(payload).array();
    }

    @Test void binaryFamiliesCommandsAndMinimumLengths() throws Exception {
        for (int family = 0; family < 256; family++) {
            assertNull(ProxyProtocol.parse(new ByteArrayInputStream(binary(0x20, family, new byte[]{1, 2}))).sourceAddress());
            int af = family >>> 4, transport = family & 15;
            int size = af == 1 ? 12 : af == 2 ? 36 : af == 3 ? 216 : 0;
            byte[] header = binary(0x21, family, new byte[(af == 0 || transport == 0) ? 0 : size]);
            if (af > 3 || transport > 2) {
                assertThrows(IOException.class, () -> ProxyProtocol.parse(new ByteArrayInputStream(header)));
            } else {
                ProxiedConnectionInfo info = ProxyProtocol.parse(new ByteArrayInputStream(header));
                if (af == 0 || transport == 0) assertNull(info.sourceAddress());
                else {
                    assertEquals(af == 1 ? "0.0.0.0" : af == 2 ? "0:0:0:0:0:0:0:0" : "", info.sourceAddress());
                    for (int length = 0; length < size; length++) {
                        byte[] shortHeader = binary(0x21, family, new byte[length]);
                        assertThrows(IOException.class, () -> ProxyProtocol.parse(new ByteArrayInputStream(shortHeader)));
                    }
                }
            }
        }
        for (int command = 0; command < 256; command++) {
            if (command == 0x20 || command == 0x21) continue;
            byte[] header = binary(command, 0, new byte[0]);
            assertThrows(IOException.class, () -> ProxyProtocol.parse(new ByteArrayInputStream(header)));
        }
    }

    @Test void unixAndMappedIpv6KeepMu3Formatting() throws Exception {
        byte[] unix = new byte[216];
        System.arraycopy("/source".getBytes(StandardCharsets.US_ASCII), 0, unix, 0, 7);
        System.arraycopy("/destination".getBytes(StandardCharsets.US_ASCII), 0, unix, 108, 12);
        ProxiedConnectionInfo info = ProxyProtocol.parse(new ByteArrayInputStream(binary(0x21, 0x32, unix)));
        assertEquals("/source", info.sourceAddress()); assertEquals("/destination", info.destinationAddress());
        byte[] ipv6 = new byte[36]; ipv6[10] = -1; ipv6[11] = -1; ipv6[12] = (byte) 192; ipv6[14] = 2; ipv6[15] = 1;
        assertEquals("0:0:0:0:0:ffff:c000:201", ProxyProtocol.parse(new ByteArrayInputStream(binary(0x21, 0x21, ipv6))).sourceAddress());
    }

    @Test void allSplitAndTruncationPointsPreserveApplicationBytes() throws Exception {
        for (byte[] header : new byte[][]{preamble(1), preamble(2),
            "PROXY UNKNOWN\r\n".getBytes(StandardCharsets.US_ASCII), binary(0x20, 0, new byte[0])}) {
            String expectedSource = ProxyProtocol.parse(new ByteArrayInputStream(header)).sourceAddress();
            for (int length = 0; length < header.length; length++) {
                byte[] truncated = java.util.Arrays.copyOf(header, length);
                assertThrows(IOException.class, () -> ProxyProtocol.parse(new ByteArrayInputStream(truncated)));
            }
            for (int split = 1; split <= header.length; split++) {
                final int boundary = split;
                byte[] bytes = java.util.Arrays.copyOf(header, header.length + 1); bytes[header.length] = 42;
                ByteArrayInputStream input = new ByteArrayInputStream(bytes) {
                    @Override public synchronized int read(byte[] b, int off, int len) {
                        return super.read(b, off, Math.min(len, pos < boundary ? boundary - pos : 1));
                    }
                };
                assertEquals(expectedSource, ProxyProtocol.parse(input).sourceAddress());
                assertEquals(42, input.read());
            }
        }
    }

    @Test void unknownIgnoresSuffixThroughCrLf() throws Exception {
        byte[] header = "PROXY UNKNOWN ignored\n\0\r suffix\r\nX".getBytes(StandardCharsets.US_ASCII);
        ByteArrayInputStream input = new ByteArrayInputStream(header);
        assertNull(ProxyProtocol.parse(input).sourceAddress());
        assertEquals('X', input.read());
    }

    @ParameterizedTest @CsvSource({"1,false", "2,false", "1,true", "2,true"})
    void h2StreamsShareConnectionMetadata(int version, boolean tls) throws Exception {
        MuServerBuilder builder = tls ? MuServerBuilder.httpsServer() : MuServerBuilder.httpServer();
        java.util.concurrent.atomic.AtomicReference<ProxiedConnectionInfo> seen = new java.util.concurrent.atomic.AtomicReference<>();
        try (MuServer server = builder.withHAProxyProtocolEnabled(true)
            .withHttp2Config(Http2ConfigBuilder.http2EnabledIfAvailable())
            .addHandler((req, resp) -> {
                ProxiedConnectionInfo info = req.connection().proxyInfo().orElseThrow();
                seen.compareAndSet(null, info); assertSame(seen.get(), info);
                resp.write(info.sourceAddress()); return true;
            }).start(); Socket raw = new Socket("localhost", server.uri().getPort())) {
            raw.getOutputStream().write(preamble(version));
            Socket transport = raw;
            if (tls) {
                SSLSocket secure = (SSLSocket) sslContextForTesting(veryTrustingTrustManager).getSocketFactory()
                    .createSocket(raw, "localhost", server.uri().getPort(), true);
                javax.net.ssl.SSLParameters parameters = secure.getSSLParameters();
                parameters.setApplicationProtocols(new String[]{"h2"}); secure.setSSLParameters(parameters);
                secure.startHandshake(); assertEquals("h2", secure.getApplicationProtocol()); transport = secure;
            }
            try (okhttp3.internal.http2.Http2Connection connection = new okhttp3.internal.http2.Http2Connection
                .Builder(true, okhttp3.internal.concurrent.TaskRunner.INSTANCE).socket(transport).build()) {
                connection.start();
                java.util.List<okhttp3.internal.http2.Http2Stream> streams = new java.util.ArrayList<>();
                for (int i = 0; i < 3; i++) {
                    streams.add(connection.newStream(java.util.List.of(
                        new okhttp3.internal.http2.Header(":method", "GET"), new okhttp3.internal.http2.Header(":path", "/"),
                        new okhttp3.internal.http2.Header(":scheme", tls ? "https" : "http"),
                        new okhttp3.internal.http2.Header(":authority", "localhost")), false));
                }
                for (var stream : streams) {
                    stream.readTimeout().timeout(3, TimeUnit.SECONDS);
                    assertEquals("200", stream.takeHeaders().get(":status"));
                    assertEquals("192.0.2.1", okio.Okio.buffer(stream.getSource()).readUtf8());
                }
            }
        }
    }

    @Test void clientCertificateComesFromTlsAfterPreamble() throws Exception {
        try (MuServer server = MuServerBuilder.httpsServer().withHAProxyProtocolEnabled(true)
            .withHttpsConfig(HttpsConfigBuilder.unsignedLocalhost()
                .withClientCertificateTrustManager(veryTrustingTrustManager)
                .withClientCertificateAuthentication(ClientCertificateAuthentication.MANDATORY))
            .addHandler((req, resp) -> {
                assertTrue(req.connection().clientCertificate().isPresent());
                resp.write(req.connection().proxyInfo().orElseThrow().sourceAddress()); return true;
            }).start(); Socket raw = new Socket("localhost", server.uri().getPort())) {
            raw.getOutputStream().write(preamble(2));
            javax.net.ssl.SSLSocketFactory factory = scaffolding.ClientCertificateTestUtils.clientForcingCertificate("client.p12").sslSocketFactory();
            try (SSLSocket secure = (SSLSocket) factory.createSocket(raw, "localhost", server.uri().getPort(), true)) {
                secure.setSoTimeout(3000); secure.startHandshake();
                try (Http1Client client = new Http1Client(secure, secure.getInputStream(), secure.getOutputStream(), server.uri())) {
                    client.writeRequestLine(Method.GET, "/").endHeaders().flush();
                    assertEquals("HTTP/1.1 200 OK", client.readLine());
                    assertEquals("192.0.2.1", client.readBody(client.readHeaders()));
                }
            }
        }
    }

    @Test void webSocketUpgradeReceivesMetadata() throws Exception {
        try (MuServer server = MuServerBuilder.httpServer().withHAProxyProtocolEnabled(true)
            .addHandler(WebSocketHandlerBuilder.webSocketHandler().withWebSocketFactory((req, headers) -> {
                headers.set("Proxy-Source", req.connection().proxyInfo().orElseThrow().sourceAddress());
                return new BaseWebSocket() {};
            })).start(); Socket socket = new Socket("localhost", server.uri().getPort())) {
            socket.setSoTimeout(3000);
            socket.getOutputStream().write(preamble(2));
            try (Http1Client client = new Http1Client(socket, socket.getInputStream(), socket.getOutputStream(), server.uri())) {
                client.writeRequestLine(Method.GET, "/").writeHeader("Host", "localhost")
                    .writeHeader("Upgrade", "websocket").writeHeader("Connection", "Upgrade")
                    .writeHeader("Sec-WebSocket-Version", "13").writeHeader("Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ==")
                    .endHeaders().flush();
                assertTrue(client.readLine().startsWith("HTTP/1.1 101"));
                assertEquals("192.0.2.1", client.readHeaders().get("Proxy-Source"));
            }
        }
    }

    @Test void metadataIsIsolatedBetweenConnectionsAndDisabledByDefault() throws Exception {
        for (boolean enabled : new boolean[]{false, true}) {
            try (MuServer server = MuServerBuilder.httpServer().withHAProxyProtocolEnabled(enabled)
                .addHandler((req, resp) -> { resp.write(req.connection().proxyInfo().map(ProxiedConnectionInfo::sourceAddress).orElse("none")); return true; }).start()) {
                for (int i = 1; i <= 2; i++) {
                    try (Socket socket = new Socket("localhost", server.uri().getPort())) {
                        socket.setSoTimeout(3000);
                        if (enabled) socket.getOutputStream().write(("PROXY TCP4 192.0.2." + i + " 127.0.0.1 1 2\r\n").getBytes(StandardCharsets.US_ASCII));
                        try (Http1Client client = new Http1Client(socket, socket.getInputStream(), socket.getOutputStream(), server.uri())) {
                            client.writeRequestLine(Method.GET, "/").endHeaders().writeRequestLine(Method.GET, "/").endHeaders().flush();
                            for (int request = 0; request < 2; request++) {
                                assertEquals("HTTP/1.1 200 OK", client.readLine());
                                assertEquals(enabled ? "192.0.2." + i : "none", client.readBody(client.readHeaders()));
                            }
                        }
                    }
                }
            }
        }
    }

    @Test void delayedExpiryCannotCloseAnEstablishedConnection() throws Exception {
        java.util.concurrent.atomic.AtomicReference<Runnable> expiry = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.ScheduledThreadPoolExecutor timer = new java.util.concurrent.ScheduledThreadPoolExecutor(1) {
            @Override public java.util.concurrent.ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initial, long period, TimeUnit unit) {
                expiry.set(command);
                return super.scheduleAtFixedRate(() -> {}, 1, 1, TimeUnit.DAYS);
            }
        };
        try (MuServer server = TestExecutionResources.configure(MuServerBuilder.httpServer(), null, null, null, timer)
            .withIdleTimeout(0, TimeUnit.SECONDS).withHAProxyProtocolEnabled(true)
            .withHAProxyProtocolTimeout(200, TimeUnit.MILLISECONDS)
            .addHandler((req, resp) -> { resp.write("ok"); return true; }).start();
             Socket socket = new Socket("localhost", server.uri().getPort())) {
            socket.setSoTimeout(3000); socket.getOutputStream().write(preamble(1));
            try (Http1Client client = new Http1Client(socket, socket.getInputStream(), socket.getOutputStream(), server.uri())) {
                for (int i = 0; i < 2; i++) {
                    client.writeRequestLine(Method.GET, "/").endHeaders().flush();
                    assertEquals("HTTP/1.1 200 OK", client.readLine());
                    assertEquals("ok", client.readBody(client.readHeaders()));
                    if (i == 0) {
                        java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(250));
                        expiry.get().run();
                    }
                }
            }
        } finally { timer.shutdownNow(); }
    }

    @Test void completedReadStillMustMeetOverallDeadline() throws Exception {
        Socket socket = new Socket() {
            @Override public int getSoTimeout() { return 0; }
            @Override public void setSoTimeout(int millis) { }
            @Override public java.io.InputStream getInputStream() {
                return new ByteArrayInputStream(preamble(2)) {
                    @Override public synchronized int read(byte[] b, int off, int len) {
                        int count = super.read(b, off, len);
                        if (available() == 0) java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(30));
                        return count;
                    }
                };
            }
        };
        assertThrows(java.net.SocketTimeoutException.class,
            () -> ProxyProtocol.read(socket, MonotonicTime.deadlineAfterMillis(20)));
    }

    private static byte[] preamble(int version) {
        if (version == 1) return "PROXY TCP4 192.0.2.1 198.51.100.2 12345 443\r\n".getBytes(StandardCharsets.US_ASCII);
        return ByteBuffer.allocate(28).put("\r\n\r\n\0\r\nQUIT\n".getBytes(StandardCharsets.US_ASCII))
            .put((byte) 0x21).put((byte) 0x11).putShort((short) 12)
            .put(new byte[]{(byte) 192, 0, 2, 1, (byte) 198, 51, 100, 2}).putShort((short) 12345).putShort((short) 443).array();
    }

    @ParameterizedTest @CsvSource({"1,false", "2,false", "1,true", "2,true"})
    void preamblePrecedesHttpOrTlsAndMetadataSurvivesReuse(int version, boolean tls) throws Exception {
        MuServerBuilder builder = tls ? MuServerBuilder.httpsServer() : MuServerBuilder.httpServer();
        try (MuServer server = builder.withHAProxyProtocolEnabled(true)
            .addHandler((req, resp) -> {
                ProxiedConnectionInfo info = req.connection().proxyInfo().orElseThrow();
                assertEquals("192.0.2.1", info.sourceAddress());
                assertEquals(12345, info.sourcePort());
                assertEquals("198.51.100.2", info.destinationAddress());
                assertEquals(443, info.destinationPort());
                assertTrue(req.connection().remoteAddress().getAddress().isLoopbackAddress());
                resp.write("proxied"); return true;
            }).start(); Socket raw = new Socket("localhost", server.uri().getPort())) {
            raw.getOutputStream().write(preamble(version)); raw.getOutputStream().flush();
            Socket transport = raw;
            if (tls) {
                SSLSocket secure = (SSLSocket) sslContextForTesting(veryTrustingTrustManager).getSocketFactory()
                    .createSocket(raw, "localhost", server.uri().getPort(), true);
                secure.startHandshake(); transport = secure;
            }
            transport.setSoTimeout(3000);
            try (Http1Client client = new Http1Client(transport, transport.getInputStream(), transport.getOutputStream(), server.uri())) {
                for (int i = 0; i < 2; i++) {
                    client.writeRequestLine(Method.GET, "/").endHeaders().flush();
                    assertEquals("HTTP/1.1 200 OK", client.readLine());
                    assertEquals("proxied", client.readBody(client.readHeaders()));
                }
            }
        }
    }

    @ParameterizedTest @ValueSource(ints = {1,2})
    void parsingConsumesExactlyThePreamble(int version) throws Exception {
        byte[] header = preamble(version);
        ByteArrayInputStream input = new ByteArrayInputStream(ByteBuffer.allocate(header.length + 3).put(header).put(new byte[]{1,2,3}).array());
        ProxiedConnectionInfo info = ProxyProtocol.parse(input);
        assertEquals("192.0.2.1", info.sourceAddress());
        assertArrayEquals(new byte[]{1,2,3}, input.readAllBytes());
    }

    @ParameterizedTest @ValueSource(strings = {
        "GET / HTTP/1.1\r\n", "PROXY TCP4 localhost 127.0.0.1 80 80\r\n",
        "PROXY TCP4 256.1.1.1 127.0.0.1 80 80\r\n", "PROXY TCP4 1.2.3.4 1.2.3.4 -1 80\r\n",
        "PROXY TCP4 1.2.3.4 1.2.3.4 65536 80\r\n", "PROXY TCP6 example.com ::1 80 80\r\n",
        "PROXY UNKNOWN\n", "PROXY TCP4 1.2.3.4 1.2.3.4 80 80\r"
    })
    void malformedPreamblesAreRejected(String header) {
        assertThrows(IOException.class, () -> ProxyProtocol.parse(new ByteArrayInputStream(header.getBytes(StandardCharsets.US_ASCII))));
    }

    @Test void unknownAndLocalHaveNoAddresses() throws Exception {
        ProxiedConnectionInfo unknown = ProxyProtocol.parse(new ByteArrayInputStream("PROXY UNKNOWN ignored\r\n".getBytes(StandardCharsets.US_ASCII)));
        assertNull(unknown.sourceAddress()); assertNull(unknown.destinationAddress()); assertEquals(0, unknown.sourcePort());
        byte[] local = preamble(2); local[12] = 0x20;
        assertEquals(unknown, ProxyProtocol.parse(new ByteArrayInputStream(local)));
    }

    @Test void ipv6AndUnknownTlvAreSupported() throws Exception {
        ProxiedConnectionInfo v1 = ProxyProtocol.parse(new ByteArrayInputStream("PROXY TCP6 ::1 2001:db8::1 0 65535\r\n".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("::1", v1.sourceAddress()); assertEquals(65535, v1.destinationPort());
        byte[] header = preamble(2);
        byte[] tlv = ByteBuffer.allocate(32).put(header).put(new byte[]{(byte) 0xee,0,1,42}).array(); tlv[15]=16;
        assertEquals("192.0.2.1", ProxyProtocol.parse(new ByteArrayInputStream(tlv)).sourceAddress());
        tlv[30]=2;
        assertThrows(IOException.class, () -> ProxyProtocol.parse(new ByteArrayInputStream(tlv)));
    }

    @Test void oversizedAndTruncatedInputsFail() {
        assertThrows(IOException.class, () -> ProxyProtocol.parse(new ByteArrayInputStream(("PROXY UNKNOWN " + "a".repeat(108)).getBytes(StandardCharsets.US_ASCII))));
        byte[] valid = preamble(2);
        for (int length = 0; length < valid.length; length++) {
            byte[] truncated = java.util.Arrays.copyOf(valid, length);
            assertThrows(IOException.class, () -> ProxyProtocol.parse(new ByteArrayInputStream(truncated)));
        }
    }

    @Test void enabledListenerRejectsOrdinaryHttpBeforeDispatch() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean handled = new java.util.concurrent.atomic.AtomicBoolean();
        try (MuServer server = MuServerBuilder.httpServer().withHAProxyProtocolEnabled(true)
            .addHandler((req, resp) -> { handled.set(true); return true; }).start();
             Socket socket = new Socket("localhost", server.uri().getPort())) {
            socket.setSoTimeout(3000);
            socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            try { assertEquals(-1, socket.getInputStream().read()); } catch (java.net.SocketException reset) { /* rejected */ }
            assertFalse(handled.get());
        }
    }

    @Test void shutdownClosesPendingPreambleReads() throws Exception {
        MuServer server = MuServerBuilder.httpServer().withHAProxyProtocolEnabled(true).start();
        try (Socket socket = new Socket("localhost", server.uri().getPort())) {
            socket.setSoTimeout(3000); socket.getOutputStream().write('P');
            server.stop(1, TimeUnit.SECONDS);
            try { assertEquals(-1, socket.getInputStream().read()); } catch (java.net.SocketException reset) { /* closed */ }
        } finally { server.stop(); }
    }
}
