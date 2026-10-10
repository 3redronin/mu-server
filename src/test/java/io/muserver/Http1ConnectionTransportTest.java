package io.muserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.*;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

@Timeout(10)
class Http1ConnectionTransportTest {
    @Test
    void realProtocolAndHandlersWorkWithSocketIndependentControls() throws Exception {
        AtomicReference<BaseHttpConnection> accepted = new AtomicReference<>();
        AtomicReference<HttpConnection> handled = new AtomicReference<>();
        var application = Executors.newSingleThreadExecutor();
        try (MuServer server = MuServerBuilder.httpServer().withRequestTimeout(2, TimeUnit.SECONDS)
            .addHandler((request, response) -> {
                if (request.uri().getPath().equals("/fixture")) {
                    accepted.set((BaseHttpConnection) request.connection());
                    response.write("ready");
                } else {
                    handled.set(request.connection());
                    response.headers().set(HeaderNames.CONTENT_LENGTH, "3");
                    response.write(request.readBodyAsString());
                }
                return true;
            }).start()) {
            // Obtain listener/application configuration. The connection exercised below owns no socket.
            try (var response = call(request(server.uri().resolve("/fixture")))) {
                assertEquals("ready", response.body().string());
            }
            BaseHttpConnection fixture = accepted.get();
            var transport = new MemoryControls();
            var connection = new Http1Connection(fixture.server, fixture.creator, transport,
                ConnectionAcceptedTime.now(), null, application);
            var wire = new ByteArrayOutputStream();
            byte[] requestBytes = ("POST /memory HTTP/1.1\r\nHost: localhost\r\n"
                + "Content-Length: 3\r\nConnection: close\r\n\r\nabc").getBytes(US_ASCII);
            connection.start(new HttpConnectionInputStream(connection, new ByteArrayInputStream(requestBytes)),
                new HttpConnectionOutputStream(connection, wire));

            assertSame(connection, handled.get());
            assertTrue(wire.toString(US_ASCII).startsWith("HTTP/1.1 200 OK\r\n"));
            assertTrue(wire.toString(US_ASCII).endsWith("\r\n\r\nabc"));
            assertEquals(List.of(2000, 0), transport.readTimeouts);
            assertTrue(transport.closed);
            assertEquals(1, connection.completedRequests());
            assertTrue(connection.activeRequests().isEmpty());
            // Security describes this transport, even when the fixture listener itself is plaintext.
            assertTrue(connection.isHttps());
            assertEquals("TLSv1.3", connection.httpsProtocol());
            assertEquals("TLS_AES_128_GCM_SHA256", connection.cipher());
            assertEquals("memory.example", connection.sniHostName().orElseThrow());
            assertEquals(transport.remoteAddress(), connection.remoteAddress());
            connection.forceShutdown();
            assertTrue(transport.aborted);
        } finally {
            application.shutdownNow();
            assertTrue(application.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    private static final class MemoryControls implements ConnectionTransport {
        final List<Integer> readTimeouts = new ArrayList<>();
        boolean closed;
        boolean aborted;
        @Override public InetSocketAddress remoteAddress() { return new InetSocketAddress("192.0.2.7", 54321); }
        @Override public InetSocketAddress localAddress() { return new InetSocketAddress("127.0.0.1", 443); }
        @Override public boolean isSecure() { return true; }
        @Override public String tlsProtocol() { return "TLSv1.3"; }
        @Override public String cipherSuite() { return "TLS_AES_128_GCM_SHA256"; }
        @Override public String sniHostName() { return "memory.example"; }
        @Override public Certificate clientCertificate() { return null; }
        @Override public void readTimeoutMillis(int timeoutMillis) { readTimeouts.add(timeoutMillis); }
        @Override public void shutdownInput() { }
        @Override public void abort() { aborted = true; }
        @Override public void close() { closed = true; }
    }
}
