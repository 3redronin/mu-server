package io.muserver;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import scaffolding.ClientUtils;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLSocket;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class TlsEngineDriverSocketTest {
    @ParameterizedTest
    @CsvSource({"TLSv1.2,true", "TLSv1.3,true", "TLSv1.2,false", "TLSv1.3,false"})
    void socketClientInteroperatesWithReadinessDrivenEngine(String protocol, boolean http2) throws Exception {
        byte[] upload = new byte[48_001];
        byte[] download = new byte[51_017];
        new Random(72).nextBytes(upload);
        new Random(84).nextBytes(download);
        var clientContext = ClientUtils.getPKCS12Context("/client-certs/client.p12", "export password");
        var config = HttpsConfigBuilder.unsignedLocalhost().withProtocols(protocol)
            .withClientCertificateTrustManager(ClientUtils.veryTrustingTrustManager)
            .withClientCertificateAuthentication(ClientCertificateAuthentication.MANDATORY).build3();
        var owner = Executors.newSingleThreadExecutor();
        var tasks = Executors.newFixedThreadPool(2);
        try (ServerSocketChannel listener = ServerSocketChannel.open()) {
            listener.bind(new InetSocketAddress("127.0.0.1", 0));
            var result = owner.submit(() -> {
                try (SocketChannel channel = listener.accept(); Selector selector = Selector.open()) {
                    channel.configureBlocking(false);
                    SelectionKey key = channel.register(selector, SelectionKey.OP_READ);
                    TlsEngineDriver driver = new TlsEngineDriver(config.createEngine(http2), tasks, selector::wakeup);
                    try {
                        ByteArrayOutputStream received = new ByteArrayOutputStream();
                        ByteBuffer response = ByteBuffer.wrap(download);
                        ByteBuffer scratch = ByteBuffer.allocate(113);
                        boolean closing = false;
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                        while (!driver.inboundDone() || !driver.outboundDone()) {
                            assertTrue(System.nanoTime() < deadline, "Socket TLS exchange did not finish");
                            boolean progress = false;
                            // A finite turn includes local work as well as bounded channel IO. Waiting
                            // solely for a new readiness edge would strand buffered records or tasks.
                            for (int i = 0; i < 64; i++) {
                                long before = driver.revision();
                                driver.advance();
                                if (driver.inputCapacity() > 0) driver.readFrom(channel, 97);
                                driver.drainEncryptedTo(channel, 83);
                                scratch.clear();
                                int count = driver.readPlaintext(scratch);
                                received.write(scratch.array(), 0, count);
                                assertTrue(received.size() <= upload.length);
                                if (received.size() == upload.length && response.hasRemaining()) driver.writePlaintext(response);
                                if (!response.hasRemaining() && !closing) {
                                    driver.closeOutbound();
                                    closing = true;
                                }
                                if (before == driver.revision()) break;
                                progress = true;
                            }
                            if (!progress && (!driver.inboundDone() || !driver.outboundDone())) {
                                key.interestOps((driver.inputCapacity() > 0 ? SelectionKey.OP_READ : 0)
                                    | (driver.encryptedOutputSize() > 0 ? SelectionKey.OP_WRITE : 0));
                                selector.select(100);
                                selector.selectedKeys().clear();
                            }
                        }
                        assertArrayEquals(upload, received.toByteArray());
                        assertEquals(protocol, driver.session().getProtocol());
                        assertEquals(http2 ? "h2" : "", driver.applicationProtocol());
                        assertEquals("transport.example", SocketConnectionTransport.requestedServerName(driver.session()));
                        assertTrue(driver.session().getPeerCertificates().length > 0);
                        return null;
                    } finally { driver.abort(new IOException("Test finished")); }
                }
            });
            try (SSLSocket client = (SSLSocket) clientContext.getSocketFactory().createSocket()) {
                client.setSoTimeout(10_000);
                client.setEnabledProtocols(new String[]{protocol});
                var parameters = client.getSSLParameters();
                parameters.setServerNames(List.of(new SNIHostName("transport.example")));
                parameters.setApplicationProtocols(new String[]{"h2", "http/1.1"});
                client.setSSLParameters(parameters);
                client.connect(listener.getLocalAddress(), 5_000);
                client.startHandshake();
                assertEquals(http2 ? "h2" : "", client.getApplicationProtocol());
                client.getOutputStream().write(upload);
                assertArrayEquals(download, client.getInputStream().readNBytes(download.length));
                assertEquals(-1, client.getInputStream().read());
            }
            result.get(10, TimeUnit.SECONDS);
        } finally {
            owner.shutdownNow();
            tasks.shutdownNow();
            assertTrue(owner.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(tasks.awaitTermination(2, TimeUnit.SECONDS));
        }
    }
}
