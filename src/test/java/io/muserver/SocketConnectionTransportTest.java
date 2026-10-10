package io.muserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import scaffolding.ClientUtils;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.HandshakeCompletedListener;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSessionContext;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.security.cert.Certificate;
import java.security.Principal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.*;
import static scaffolding.ClientUtils.veryTrustingTrustManager;
import static scaffolding.ServerUtils.httpsServerForTest;

@Timeout(10)
class SocketConnectionTransportTest {
    @Test
    void metadataFollowsTheCurrentEstablishedSession() throws Exception {
        AtomicReference<SSLSession> session = new AtomicReference<>(new SessionWithoutSni());
        try (Socket accepted = new Socket();
             SocketConnectionTransport transport = new SocketConnectionTransport(new SSLSocket() {
                 @Override public SocketAddress getRemoteSocketAddress() { return new InetSocketAddress("127.0.0.1", 12345); }
                 @Override public SocketAddress getLocalSocketAddress() { return new InetSocketAddress("127.0.0.1", 443); }
                 @Override public SSLSession getSession() { return session.get(); }
                 @Override public String[] getSupportedCipherSuites() { return new String[0]; }
                 @Override public String[] getEnabledCipherSuites() { return new String[0]; }
                 @Override public void setEnabledCipherSuites(String[] suites) { }
                 @Override public String[] getSupportedProtocols() { return new String[0]; }
                 @Override public String[] getEnabledProtocols() { return new String[0]; }
                 @Override public void setEnabledProtocols(String[] protocols) { }
                 @Override public void addHandshakeCompletedListener(HandshakeCompletedListener listener) { }
                 @Override public void removeHandshakeCompletedListener(HandshakeCompletedListener listener) { }
                 @Override public void startHandshake() { fail("Metadata lookup must not start a handshake"); }
                 @Override public void setUseClientMode(boolean mode) { }
                 @Override public boolean getUseClientMode() { return false; }
                 @Override public void setNeedClientAuth(boolean need) { }
                 @Override public boolean getNeedClientAuth() { return false; }
                 @Override public void setWantClientAuth(boolean want) { }
                 @Override public boolean getWantClientAuth() { return false; }
                 @Override public void setEnableSessionCreation(boolean enable) { }
                 @Override public boolean getEnableSessionCreation() { return false; }
             }, accepted, null)) {
            assertEquals("TLS_AES_128_GCM_SHA256", transport.cipherSuite());
            assertNull(transport.sniHostName());
            session.set(new SessionWithoutSni() {
                @Override public String getCipherSuite() { return "TLS_AES_256_GCM_SHA384"; }
                @Override public List<SNIServerName> getRequestedServerNames() { return List.of(new SNIHostName("current.example")); }
            });
            assertEquals("TLS_AES_256_GCM_SHA384", transport.cipherSuite());
            assertEquals("current.example", transport.sniHostName());
        }
    }

    @Test
    void missingProviderSniIntrospectionYieldsNoHostName() {
        assertNull(SocketConnectionTransport.requestedServerName(new SessionWithoutSni()));
    }

    @Test
    void sniMetadataDoesNotRequireProvidersToReturnTheJdkHostNameClass() {
        var session = new SessionWithoutSni() {
            @Override public List<SNIServerName> getRequestedServerNames() {
                return List.of(new SNIServerName(0, "provider.example".getBytes(US_ASCII)) { });
            }
        };
        assertEquals("provider.example", SocketConnectionTransport.requestedServerName(session));
    }

    @Test
    void sniffedBytesTimeoutAndInputShutdownPreserveTheOutputPath() throws Exception {
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             Socket client = new Socket(listener.getInetAddress(), listener.getLocalPort());
             Socket accepted = listener.accept();
             SocketConnectionTransport transport = new SocketConnectionTransport(accepted, accepted, null)) {
            client.setSoTimeout(3000);
            byte[] bytes = "GET / HTTP/1.1\r\n".getBytes(US_ASCII);
            client.getOutputStream().write(bytes);
            PushbackInputStream sniffed = new PushbackInputStream(accepted.getInputStream(), 4);
            byte[] prefix = sniffed.readNBytes(4);
            sniffed.unread(prefix);
            assertArrayEquals(bytes, transport.input(sniffed).readNBytes(bytes.length));

            InputStream input = transport.input(null);
            transport.readTimeoutMillis(30);
            assertThrows(SocketTimeoutException.class, input::read);
            transport.readTimeoutMillis(0);
            transport.shutdownInput();
            assertEquals(-1, input.read());
            transport.output().write("ok".getBytes(US_ASCII));
            assertArrayEquals("ok".getBytes(US_ASCII), client.getInputStream().readNBytes(2));

            transport.close();
            transport.abort();
            assertEquals(client.getLocalSocketAddress(), transport.remoteAddress());
            assertEquals(client.getRemoteSocketAddress(), transport.localAddress());
            assertFalse(transport.isSecure());
            assertNull(transport.tlsProtocol());
            assertNull(transport.cipherSuite());
            assertNull(transport.sniHostName());
            assertNull(transport.clientCertificate());
        }
    }

    @Test
    void abortUnblocksInputEvenWhileWrapperCloseIsWaiting() throws Exception {
        CountDownLatch closing = new CountDownLatch(1);
        CountDownLatch releaseClose = new CountDownLatch(1);
        CountDownLatch reading = new CountDownLatch(1);
        var tasks = Executors.newFixedThreadPool(3);
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             Socket client = new Socket(listener.getInetAddress(), listener.getLocalPort());
             Socket accepted = listener.accept()) {
            // Model a TLS wrapper stuck in orderly close. Abort must use the accepted socket
            // independently, without sharing the wrapper's close lock or waiting for its alert.
            Socket wrapper = new Socket() {
                @Override public SocketAddress getRemoteSocketAddress() { return accepted.getRemoteSocketAddress(); }
                @Override public SocketAddress getLocalSocketAddress() { return accepted.getLocalSocketAddress(); }
                @Override public InputStream getInputStream() throws IOException { return accepted.getInputStream(); }
                @Override public void close() throws IOException {
                    closing.countDown();
                    try {
                        if (!releaseClose.await(5, TimeUnit.SECONDS)) throw new IOException("Close was not released");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException(interrupted);
                    }
                    accepted.close();
                }
            };
            SocketConnectionTransport transport = new SocketConnectionTransport(wrapper, accepted, null);
            var read = tasks.submit(() -> {
                InputStream input = transport.input(null);
                reading.countDown();
                return assertThrows(IOException.class, input::read);
            });
            assertTrue(reading.await(2, TimeUnit.SECONDS));
            var close = tasks.submit(() -> { transport.close(); return null; });
            assertTrue(closing.await(2, TimeUnit.SECONDS));
            tasks.submit(() -> { transport.abort(); return null; }).get(2, TimeUnit.SECONDS);
            assertNotNull(read.get(2, TimeUnit.SECONDS));
            assertFalse(close.isDone(), "Abort must not wait for orderly close to complete");
            releaseClose.countDown();
            close.get(2, TimeUnit.SECONDS);
        } finally {
            releaseClose.countDown();
            tasks.shutdownNow();
            assertTrue(tasks.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"TLSv1.2", "TLSv1.3"})
    void negotiatedMetadataSurvivesClose(String protocol) throws Exception {
        AtomicReference<HttpConnection> captured = new AtomicReference<>();
        try (MuServer server = httpsServerForTest("https")
            .withHttpsConfig(HttpsConfigBuilder.unsignedLocalhost().withProtocols(protocol)
                .withClientCertificateTrustManager(veryTrustingTrustManager()))
            .addHandler((request, response) -> {
                captured.set(request.connection());
                response.headers().set(HeaderNames.CONTENT_LENGTH, "2");
                response.write("ok");
                return true;
            }).start()) {
            var context = ClientUtils.getPKCS12Context("/client-certs/client.p12", "export password");
            String cipher;
            Certificate certificate;
            SocketAddress clientAddress;
            try (SSLSocket client = (SSLSocket) context.getSocketFactory().createSocket(server.uri().getHost(), server.uri().getPort())) {
                client.setSoTimeout(3000);
                client.setEnabledProtocols(new String[]{protocol});
                var parameters = client.getSSLParameters();
                parameters.setServerNames(List.of(new SNIHostName("transport.example")));
                client.setSSLParameters(parameters);
                client.startHandshake();
                cipher = client.getSession().getCipherSuite();
                certificate = client.getSession().getLocalCertificates()[0];
                clientAddress = client.getLocalSocketAddress();
                client.getOutputStream().write("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".getBytes(US_ASCII));
                assertTrue(new String(client.getInputStream().readAllBytes(), US_ASCII).endsWith("ok"));
            }
            server.stop();
            HttpConnection connection = captured.get();
            assertNotNull(connection);
            assertTrue(connection.isHttps());
            assertEquals(protocol, connection.httpsProtocol());
            assertEquals(cipher, connection.cipher());
            assertEquals("transport.example", connection.sniHostName().orElseThrow());
            assertEquals(certificate, connection.clientCertificate().orElseThrow());
            assertEquals(clientAddress, connection.remoteAddress());
        }
    }

    /** A provider may inherit ExtendedSSLSession's unsupported SNI implementation. */
    @SuppressWarnings({"deprecation", "removal"})
    private static class SessionWithoutSni extends ExtendedSSLSession {
        @Override public String[] getLocalSupportedSignatureAlgorithms() { return new String[0]; }
        @Override public String[] getPeerSupportedSignatureAlgorithms() { return new String[0]; }
        @Override public byte[] getId() { return new byte[0]; }
        @Override public SSLSessionContext getSessionContext() { return null; }
        @Override public long getCreationTime() { return 0; }
        @Override public long getLastAccessedTime() { return 0; }
        @Override public void invalidate() { }
        @Override public boolean isValid() { return true; }
        @Override public void putValue(String name, Object value) { }
        @Override public Object getValue(String name) { return null; }
        @Override public void removeValue(String name) { }
        @Override public String[] getValueNames() { return new String[0]; }
        @Override public Certificate[] getPeerCertificates() { return new Certificate[0]; }
        @Override public Certificate[] getLocalCertificates() { return new Certificate[0]; }
        @Override public javax.security.cert.X509Certificate[] getPeerCertificateChain() { return new javax.security.cert.X509Certificate[0]; }
        @Override public Principal getPeerPrincipal() { return null; }
        @Override public Principal getLocalPrincipal() { return null; }
        @Override public String getCipherSuite() { return "TLS_AES_128_GCM_SHA256"; }
        @Override public String getProtocol() { return "TLSv1.3"; }
        @Override public String getPeerHost() { return "localhost"; }
        @Override public int getPeerPort() { return 443; }
        @Override public int getPacketBufferSize() { return 16384; }
        @Override public int getApplicationBufferSize() { return 16384; }
    }
}
