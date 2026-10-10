package io.muserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import scaffolding.ClientUtils;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManager;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class TlsEngineDriverTest {
    @ParameterizedTest
    @CsvSource({"TLSv1.2,1", "TLSv1.2,4096", "TLSv1.3,1", "TLSv1.3,4096"})
    void fragmentedMutualTlsNegotiatesMetadataAndExchangesBothDirections(String protocol, int fragment) throws Exception {
        try (var pair = new Pair(protocol, fragment, true, true)) {
            pair.handshake();
            assertEquals(protocol, pair.server.session().getProtocol());
            assertEquals("h2", pair.server.applicationProtocol());
            assertEquals("h2", pair.client.applicationProtocol());
            assertEquals("transport.example", SocketConnectionTransport.requestedServerName(pair.server.session()));
            assertEquals(pair.client.session().getLocalCertificates()[0], pair.server.session().getPeerCertificates()[0]);
            byte[] upload = new byte[80_000];
            byte[] download = new byte[90_000];
            new Random(17).nextBytes(upload);
            new Random(41).nextBytes(download);
            ByteBuffer clientSource = ByteBuffer.allocateDirect(upload.length).put(upload).flip().asReadOnlyBuffer();
            ByteBuffer serverSource = ByteBuffer.wrap(download).asReadOnlyBuffer();
            pair.until(() -> !clientSource.hasRemaining() && !serverSource.hasRemaining()
                && pair.clientReceived.size() == download.length && pair.serverReceived.size() == upload.length, () -> {
                if (clientSource.hasRemaining()) pair.client.writePlaintext(clientSource);
                if (serverSource.hasRemaining()) pair.server.writePlaintext(serverSource);
            });
            assertArrayEquals(upload, pair.serverReceived.toByteArray());
            assertArrayEquals(download, pair.clientReceived.toByteArray());
            pair.client.closeOutbound();
            pair.server.closeOutbound();
            pair.until(() -> pair.client.inboundDone() && pair.server.inboundDone()
                && pair.client.outboundDone() && pair.server.outboundDone(), () -> { });
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"TLSv1.2", "TLSv1.3"})
    void plaintextIsAcknowledgedOnlyAfterTheLastEncryptedByteDrains(String protocol) throws Exception {
        try (var pair = new Pair(protocol, 4096, false, false)) {
            pair.handshake();
            ByteBuffer source = ByteBuffer.wrap(new byte[]{1, 2, 3, 4});
            assertEquals(0, pair.server.writePlaintext(source));
            assertEquals(0, source.position());
            assertTrue(pair.server.encryptedOutputSize() > source.remaining());
            assertEquals(0, pair.server.drainEncryptedTo(new Channel(bytes -> 0), 8192));
            assertEquals(0, pair.server.writePlaintext(source));
            int packet = pair.server.encryptedOutputSize();
            assertEquals(packet - 1, pair.server.drainEncryptedTo(new Channel(pair.client::receive), packet - 1));
            assertEquals(0, pair.server.writePlaintext(source));
            assertEquals(0, source.position());
            assertEquals(1, pair.server.drainEncryptedTo(new Channel(pair.client::receive), 1));
            source.limit(2); // A later transport turn can have a smaller plaintext drain budget.
            assertEquals(2, pair.server.writePlaintext(source));
            source.limit(4);
            assertEquals(2, pair.server.writePlaintext(source));
            assertEquals(4, source.position());
            pair.until(() -> pair.clientReceived.size() == 4, () -> { });
            assertArrayEquals(new byte[]{1, 2, 3, 4}, pair.clientReceived.toByteArray());
        }
    }

    @Test
    void tls13PeerCloseNotifyAllowsTheResponseDirectionToFinish() throws Exception {
        try (var pair = new Pair("TLSv1.3", 7, false, false)) {
            pair.handshake();
            pair.client.closeOutbound();
            pair.until(pair.server::inboundDone, () -> { });
            assertFalse(pair.server.outboundDone());
            ByteBuffer response = ByteBuffer.wrap(new byte[]{5, 6, 7});
            pair.until(() -> !response.hasRemaining() && pair.clientReceived.size() == 3, () -> {
                if (response.hasRemaining()) pair.server.writePlaintext(response);
            });
            pair.server.closeOutbound();
            pair.until(() -> pair.client.inboundDone() && pair.server.outboundDone(), () -> { });
            assertArrayEquals(new byte[]{5, 6, 7}, pair.clientReceived.toByteArray());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"TLSv1.2", "TLSv1.3"})
    void applicationDataSurvivesRenegotiationOrKeyUpdate(String protocol) throws Exception {
        try (var pair = new Pair(protocol, 7, true, true)) {
            pair.handshake();
            // SunJSSE initiates renegotiation for TLS 1.2 and KeyUpdate for TLS 1.3.
            pair.serverEngine.beginHandshake();
            pair.until(() -> !pair.client.tasksPending() && !pair.server.tasksPending()
                && pair.clientEngine.getHandshakeStatus() == javax.net.ssl.SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
                && pair.serverEngine.getHandshakeStatus() == javax.net.ssl.SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
                && pair.client.encryptedOutputSize() == 0 && pair.server.encryptedOutputSize() == 0, () -> { });
            ByteBuffer request = ByteBuffer.wrap(new byte[]{1, 2, 3});
            ByteBuffer response = ByteBuffer.wrap(new byte[]{4, 5, 6});
            pair.until(() -> !request.hasRemaining() && !response.hasRemaining()
                && pair.clientReceived.size() == 3 && pair.serverReceived.size() == 3, () -> {
                if (request.hasRemaining()) pair.client.writePlaintext(request);
                if (response.hasRemaining()) pair.server.writePlaintext(response);
            });
            assertArrayEquals(new byte[]{1, 2, 3}, pair.serverReceived.toByteArray());
            assertArrayEquals(new byte[]{4, 5, 6}, pair.clientReceived.toByteArray());
            assertEquals(protocol, pair.server.session().getProtocol());
            assertEquals(pair.client.session().getLocalCertificates()[0], pair.server.session().getPeerCertificates()[0]);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"TLSv1.2", "TLSv1.3"})
    void reusedContextsKeepTheSniCertificateAcrossConnectionsAndRehandshakes(String protocol) throws Exception {
        SSLContext clientContext = Pair.clientContext(false);
        HttpsConfig serverConfig = HttpsConfigBuilder.httpsConfig().withProtocols(protocol)
            .withKeystoreType("JKS").withKeystorePassword("MY_PASSWORD").withKeyPassword("MY_PASSWORD")
            .withKeystoreFromClasspath("/jks-keystore-combine.jks").withDefaultAlias("mykey-2").build3();
        for (int connection = 0; connection < 3; connection++) {
            try (var pair = new Pair(protocol, 4096, clientContext, serverConfig, "test-1.com")) {
                pair.handshake();
                if (connection == 2) pair.server.session().invalidate(); // Also force a full TLS 1.2 renegotiation.
                pair.serverEngine.beginHandshake();
                pair.until(() -> !pair.client.tasksPending() && !pair.server.tasksPending()
                    && pair.clientEngine.getHandshakeStatus() == javax.net.ssl.SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
                    && pair.serverEngine.getHandshakeStatus() == javax.net.ssl.SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
                    && pair.client.encryptedOutputSize() == 0 && pair.server.encryptedOutputSize() == 0, () -> { });
                X509Certificate certificate = (X509Certificate) pair.client.session().getPeerCertificates()[0];
                assertTrue(certificate.getSubjectX500Principal().getName().contains("CN=TEST-1"));
                assertEquals("test-1.com", SocketConnectionTransport.requestedServerName(pair.server.session()));
                pair.client.closeOutbound();
                pair.server.closeOutbound();
                pair.until(() -> pair.client.inboundDone() && pair.server.inboundDone()
                    && pair.client.outboundDone() && pair.server.outboundDone(), () -> { });
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"TLSv1.2", "TLSv1.3"})
    void stalledOutputDoesNotPreventDecryptingTheOtherDirection(String protocol) throws Exception {
        try (var pair = new Pair(protocol, 4096, false, false)) {
            pair.handshake();
            ByteBuffer response = ByteBuffer.wrap(new byte[100]);
            pair.server.writePlaintext(response);
            pair.serverCanWrite = false;
            ByteBuffer upload = ByteBuffer.wrap(new byte[]{8, 9, 10});
            pair.until(() -> pair.serverReceived.size() == 3 && !upload.hasRemaining(), () -> {
                if (upload.hasRemaining()) pair.client.writePlaintext(upload);
            });
            assertEquals(0, response.position());
            assertTrue(pair.server.encryptedOutputSize() > 0);
            pair.serverCanWrite = true;
            pair.until(() -> !response.hasRemaining() && pair.clientReceived.size() == 100, () -> pair.server.writePlaintext(response));
        }
    }

    @Test
    void plaintextBackpressureRetainsOneDecodedRecordAndResumesWithoutNewNetworkInput() throws Exception {
        try (var pair = new Pair("TLSv1.3", 4096, false, false)) {
            pair.handshake();
            pair.serverConsumesPlaintext = false;
            byte[] expected = new byte[32_000];
            new Random(22).nextBytes(expected);
            ByteBuffer upload = ByteBuffer.wrap(expected);
            pair.until(() -> pair.server.plaintextSize() > 0, () -> pair.client.writePlaintext(upload));
            int first = pair.server.plaintextSize();
            for (int i = 0; i < 20; i++) {
                pair.step();
                if (upload.hasRemaining()) pair.client.writePlaintext(upload);
            }
            assertEquals(first, pair.server.plaintextSize());
            assertTrue(pair.server.inputCapacity() < pair.server.session().getPacketBufferSize());
            pair.serverConsumesPlaintext = true;
            pair.until(() -> !upload.hasRemaining() && pair.serverReceived.size() == expected.length, () -> {
                if (upload.hasRemaining()) pair.client.writePlaintext(upload);
            });
            assertArrayEquals(expected, pair.serverReceived.toByteArray());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"TLSv1.2", "TLSv1.3"})
    void abruptEofAndTruncatedRecordsNeverBecomeCleanTlsEof(String protocol) throws Exception {
        for (boolean truncated : new boolean[]{false, true}) {
            try (var pair = new Pair(protocol, 4096, false, false)) {
                pair.handshake();
                if (truncated) {
                    pair.client.writePlaintext(ByteBuffer.wrap(new byte[]{1, 2, 3}));
                    pair.client.drainEncryptedTo(new Channel(pair.server::receive), pair.client.encryptedOutputSize() - 1);
                }
                pair.server.endOfInput();
                assertThrows(SSLException.class, () -> {
                    for (int i = 0; i < 10; i++) pair.server.advance();
                });
                assertFalse(pair.server.inboundDone());
                assertThrows(SSLException.class, pair.server::advance);
                assertEquals(0, pair.server.plaintextSize());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"TLSv1.2", "TLSv1.3"})
    void corruptedRecordFailsWithoutPublishingPlaintext(String protocol) throws Exception {
        try (var pair = new Pair(protocol, 4096, false, false)) {
            pair.handshake();
            pair.client.writePlaintext(ByteBuffer.wrap(new byte[]{1, 2, 3}));
            ByteBuffer packet = ByteBuffer.allocate(pair.client.encryptedOutputSize());
            pair.client.drainEncryptedTo(new Channel(source -> {
                int count = source.remaining(); packet.put(source); return count;
            }), packet.capacity());
            packet.flip();
            packet.put(packet.limit() - 1, (byte) (packet.get(packet.limit() - 1) ^ 1));
            pair.server.receive(packet);
            assertThrows(SSLException.class, pair.server::advance);
            assertEquals(0, pair.server.plaintextSize());
            assertThrows(SSLException.class, () -> pair.server.readPlaintext(ByteBuffer.allocate(10)));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"TLSv1.2", "TLSv1.3"})
    void mandatoryClientCertificateCannotBeSkipped(String protocol) throws Exception {
        try (var pair = new Pair(protocol, 4096, true, false)) {
            assertThrows(SSLException.class, pair::handshake);
            assertFalse(pair.server.handshakeComplete());
            SSLException alert = assertThrows(SSLException.class, () -> {
                for (int i = 0; i < 10_000; i++) {
                    pair.server.advanceFailureClose();
                    pair.server.drainFailureTo(new Channel(pair.client::receive), 1);
                    pair.client.advance();
                }
            });
            assertTrue(alert.getMessage().contains("alert"), alert.toString());
            assertTrue(pair.server.failureCloseDone());
        }
    }

    @FunctionalInterface
    interface IoAction { void run() throws Exception; }

    @FunctionalInterface
    interface Write { int write(ByteBuffer bytes) throws IOException; }

    static final class Channel implements WritableByteChannel {
        final Write write;
        Channel(Write write) { this.write = write; }
        @Override public int write(ByteBuffer source) throws IOException { return write.write(source); }
        @Override public boolean isOpen() { return true; }
        @Override public void close() { }
    }

    static final class Pair implements AutoCloseable {
        final ExecutorService tasks = Executors.newFixedThreadPool(2);
        final Semaphore ready = new Semaphore(0);
        final TlsEngineDriver client;
        final TlsEngineDriver server;
        final SSLEngine clientEngine;
        final SSLEngine serverEngine;
        final ByteArrayOutputStream clientReceived = new ByteArrayOutputStream();
        final ByteArrayOutputStream serverReceived = new ByteArrayOutputStream();
        final int fragment;
        boolean serverCanWrite = true;
        boolean serverConsumesPlaintext = true;

        Pair(String protocol, int fragment, boolean mandatoryClientCertificate, boolean provideClientCertificate) throws Exception {
            this(protocol, fragment, clientContext(provideClientCertificate), serverConfig(protocol, mandatoryClientCertificate), "transport.example");
        }

        Pair(String protocol, int fragment, SSLContext clientContext, HttpsConfig serverConfig, String host) throws Exception {
            this.fragment = fragment;
            clientEngine = clientContext.createSSLEngine(host, 443);
            clientEngine.setUseClientMode(true);
            clientEngine.setEnabledProtocols(new String[]{protocol});
            var parameters = clientEngine.getSSLParameters();
            parameters.setServerNames(List.of(new SNIHostName(host)));
            parameters.setApplicationProtocols(new String[]{"h2", "http/1.1"});
            clientEngine.setSSLParameters(parameters);
            client = new TlsEngineDriver(clientEngine, tasks, ready::release);
            serverEngine = serverConfig.createEngine(true);
            server = new TlsEngineDriver(serverEngine, tasks, ready::release);
        }

        static SSLContext clientContext(boolean provideClientCertificate) throws Exception {
            if (provideClientCertificate) return ClientUtils.getPKCS12Context("/client-certs/client.p12", "export password");
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{ClientUtils.veryTrustingTrustManager}, null);
            return context;
        }

        static HttpsConfig serverConfig(String protocol, boolean mandatoryClientCertificate) {
            var config = HttpsConfigBuilder.unsignedLocalhost().withProtocols(protocol);
            if (mandatoryClientCertificate) config.withClientCertificateTrustManager(ClientUtils.veryTrustingTrustManager)
                .withClientCertificateAuthentication(ClientCertificateAuthentication.MANDATORY);
            return config.build3();
        }

        void handshake() throws Exception {
            until(() -> client.handshakeComplete() && server.handshakeComplete(), () -> { });
            // Drain post-handshake records as well; they cannot be mistaken for the next app write.
            until(() -> client.encryptedOutputSize() == 0 && server.encryptedOutputSize() == 0
                && !client.tasksPending() && !server.tasksPending(), () -> { });
            for (int i = 0; i < 8; i++) step();
        }

        void until(BooleanSupplier done, IoAction action) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!done.getAsBoolean()) {
                assertTrue(System.nanoTime() < deadline, "TLS pair did not make progress");
                boolean progress = step();
                long clientBefore = client.revision();
                long serverBefore = server.revision();
                action.run();
                progress |= clientBefore != client.revision() || serverBefore != server.revision();
                if (!progress) ready.tryAcquire(1, TimeUnit.MILLISECONDS);
            }
        }

        boolean step() throws Exception {
            long clientBefore = client.revision();
            long serverBefore = server.revision();
            client.advance();
            server.advance();
            if (server.inputCapacity() > 0) client.drainEncryptedTo(new Channel(server::receive), fragment);
            if (serverCanWrite && client.inputCapacity() > 0) server.drainEncryptedTo(new Channel(client::receive), fragment);
            drainPlaintext(client, clientReceived);
            if (serverConsumesPlaintext) drainPlaintext(server, serverReceived);
            return clientBefore != client.revision() || serverBefore != server.revision();
        }

        static void drainPlaintext(TlsEngineDriver driver, ByteArrayOutputStream target) throws IOException {
            if (driver.plaintextSize() == 0) return;
            assertTrue(driver.handshakeComplete(), "Unauthenticated plaintext was published");
            ByteBuffer bytes = ByteBuffer.allocate(driver.plaintextSize());
            driver.readPlaintext(bytes);
            target.write(bytes.array());
        }

        @Override public void close() throws Exception {
            client.abort(new IOException("Test finished"));
            server.abort(new IOException("Test finished"));
            tasks.shutdownNow();
            assertTrue(tasks.awaitTermination(2, TimeUnit.SECONDS));
        }
    }
}
