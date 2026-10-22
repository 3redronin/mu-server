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
    @ParameterizedTest @ValueSource(strings = {"TLSv1.2", "TLSv1.3"})
    void renegotiationOrKeyUpdateCanFollowAnUndrainedCiphertextBatch(String protocol) throws Exception {
        try (var pair = new Pair(protocol, 97, false, false)) {
            pair.handshake();
            byte[] expected = new byte[100000];
            new Random(562).nextBytes(expected);
            ByteBuffer source = ByteBuffer.wrap(expected);
            assertEquals(0, pair.server.writePlaintext(source));
            assertTrue(pair.server.encryptedOutputSize() > 32768);
            pair.serverEngine.beginHandshake();
            pair.until(() -> !source.hasRemaining() && pair.clientReceived.size() == expected.length,
                () -> pair.server.writePlaintext(source));
            assertArrayEquals(expected, pair.clientReceived.toByteArray());
            // Receiving the old batch alone can finish before the peer processes the
            // renegotiation/KeyUpdate. Require the transition and subsequent traffic too.
            pair.until(() -> !pair.client.tasksPending() && !pair.server.tasksPending()
                && pair.clientEngine.getHandshakeStatus() == javax.net.ssl.SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
                && pair.serverEngine.getHandshakeStatus() == javax.net.ssl.SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
                && pair.client.encryptedOutputSize() == 0 && pair.server.encryptedOutputSize() == 0, () -> { });
            ByteBuffer request = ByteBuffer.wrap(new byte[]{1, 2, 3});
            ByteBuffer response = ByteBuffer.wrap(new byte[]{4, 5, 6});
            pair.until(() -> !request.hasRemaining() && !response.hasRemaining()
                && pair.serverReceived.size() == 3 && pair.clientReceived.size() == expected.length + 3, () -> {
                if (request.hasRemaining()) pair.client.writePlaintext(request);
                if (response.hasRemaining()) pair.server.writePlaintext(response);
            });
            assertArrayEquals(new byte[]{1, 2, 3}, pair.serverReceived.toByteArray());
            assertArrayEquals(new byte[]{4, 5, 6}, java.util.Arrays.copyOfRange(
                pair.clientReceived.toByteArray(), expected.length, expected.length + 3));
        }
    }

    @Test void failedSocketWriteReturnsTheCiphertextBatchWithoutAcknowledgingItsPlaintext() throws Exception {
        try (var pair = new Pair("TLSv1.3", 4096, false, false)) {
            pair.handshake();
            ByteBuffer source = ByteBuffer.allocate(65536);
            pair.server.writePlaintext(source);
            assertNull(pair.serverOutputBuffers.acquire());
            var cause = new IOException("Partial network write failed");
            assertSame(cause, assertThrows(IOException.class, () -> pair.server.drainEncryptedTo(new Channel(bytes -> {
                bytes.get(); throw cause;
            }), 17)));
            assertEquals(0, pair.server.encryptedOutputSize());
            assertEquals(0, source.position());
            assertSame(cause, assertThrows(IOException.class, () -> pair.server.writePlaintext(source)));
            ByteBuffer lease = pair.serverOutputBuffers.acquire();
            assertNotNull(lease); pair.serverOutputBuffers.release(lease);
        }
    }

    @ParameterizedTest
    @CsvSource({"TLSv1.2,false", "TLSv1.2,true", "TLSv1.3,false", "TLSv1.3,true"})
    void coalescedRecordsRetainTheSourceUntilTheLastCiphertextByte(String protocol, boolean direct) throws Exception {
        try (var pair = new Pair(protocol, 97, false, false)) {
            pair.handshake();
            byte[] expected = new byte[65536];
            new Random(529).nextBytes(expected);
            ByteBuffer storage = direct ? ByteBuffer.allocateDirect(expected.length + 11) : ByteBuffer.allocate(expected.length + 11);
            storage.position(7).put(expected).flip().position(7);
            ByteBuffer source = storage.asReadOnlyBuffer();
            var output = new TransportOutputBuffer(8201, 65536, () -> {});
            var progress = new TransportWriteProgress(() -> {});
            var write = output.asynchronousWriter().write(source, progress);
            var sink = new Channel(pair.server::writePlaintext);
            assertEquals(0, output.drainTo(sink, 65536));
            int packet = pair.server.encryptedOutputSize();
            assertTrue(packet > 3 * 16384, "Several records must share the ciphertext batch");
            assertNull(pair.serverOutputBuffers.acquire(), "A stalled batch holds the only shared lease");
            ByteArrayOutputStream ciphertext = new ByteArrayOutputStream();
            var channel = new Channel(bytes -> {
                byte[] part = new byte[bytes.remaining()];
                bytes.get(part); ciphertext.write(part); return part.length;
            });
            assertEquals(0, pair.server.drainEncryptedTo(new Channel(bytes -> 0), packet));
            assertEquals(packet - 1, pair.server.drainEncryptedTo(channel, packet - 1));
            assertEquals(0, output.drainTo(sink, 1));
            assertFalse(progress.reached(1));
            assertNull(pair.serverOutputBuffers.acquire());
            assertEquals(1, pair.server.drainEncryptedTo(channel, 1));
            ByteBuffer returned = pair.serverOutputBuffers.acquire();
            assertNotNull(returned, "Ciphertext drainage releases the lease before plaintext acknowledgement");
            returned.clear().put((byte) 99).flip(); // Another connection may already be using the lease.
            pair.serverOutputBuffers.release(returned);
            assertEquals(1, output.drainTo(sink, 1));
            assertTrue(progress.reached(1));
            assertFalse(write.isDone());
            ByteBuffer encrypted = ByteBuffer.wrap(ciphertext.toByteArray());
            int[] budgets = {3, 65536, 1, 8192, 97};
            int[] turn = {0};
            pair.until(() -> write.isDone() && pair.clientReceived.size() == expected.length, () -> {
                // Deliver the captured prefix before allowing the final record onto the wire.
                if (encrypted.hasRemaining()) pair.client.receive(encrypted);
                else output.drainTo(sink, budgets[turn[0]++ % budgets.length]);
            });
            write.get();
            assertArrayEquals(expected, pair.clientReceived.toByteArray());
            assertEquals(7, source.position());
            assertEquals(0, pair.server.encryptedOutputSize());
        }
    }

    @Test
    void busySharedCiphertextStorageFallsBackToTheConnectionBufferAndAbortReturnsItsLease() throws Exception {
        try (var pair = new Pair("TLSv1.3", 4096, false, false)) {
            pair.handshake();
            ByteBuffer lease = pair.serverOutputBuffers.acquire();
            assertNotNull(lease);
            ByteBuffer source = ByteBuffer.wrap(new byte[65536]);
            pair.server.writePlaintext(source);
            assertTrue(pair.server.encryptedOutputSize() <= pair.server.session().getPacketBufferSize());
            pair.serverOutputBuffers.release(lease);
            pair.until(() -> source.position() > 0, () -> pair.server.writePlaintext(source));
            pair.server.writePlaintext(source);
            assertNull(pair.serverOutputBuffers.acquire());
            pair.server.abort(new IOException("Cancel while the coalesced ciphertext is stalled"));
            lease = pair.serverOutputBuffers.acquire();
            assertNotNull(lease);
            pair.serverOutputBuffers.release(lease);
            assertThrows(IOException.class, () -> pair.server.writePlaintext(source));
        }
    }

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

    @ParameterizedTest @ValueSource(strings = {"TLSv1.2", "TLSv1.3"})
    void transportProgressPublishesOnlyAfterTheWholeCiphertextRecordDrains(String protocol) throws Exception {
        try (var pair = new Pair(protocol, 4096, false, false)) {
            pair.handshake();
            var output = new TransportOutputBuffer(4, () -> {});
            var progress = new TransportWriteProgress(() -> {});
            byte[] bytes = {1, 2, 3, 4, 5, 6, 7, 8};
            var write = output.asynchronousWriter().write(ByteBuffer.wrap(bytes), progress);
            var sink = new Channel(pair.server::writePlaintext);
            assertEquals(0, output.drainTo(sink, 4));
            assertFalse(progress.reached(1));
            int packet = pair.server.encryptedOutputSize();
            assertTrue(packet > 4);
            assertEquals(packet - 1, pair.server.drainEncryptedTo(new Channel(pair.client::receive), packet - 1));
            assertEquals(0, output.drainTo(sink, 4));
            assertFalse(progress.reached(1));
            assertEquals(1, pair.server.drainEncryptedTo(new Channel(pair.client::receive), 1));
            assertEquals(4, output.drainTo(sink, 4));
            assertTrue(progress.reached(4));
            assertFalse(progress.reached(5));
            assertFalse(write.isDone(), "The later record still owns the transport write");
            pair.until(() -> write.isDone() && pair.clientReceived.size() == bytes.length,
                () -> output.drainTo(sink, 4));
            write.get();
            assertTrue(progress.reached(bytes.length));
            assertArrayEquals(bytes, pair.clientReceived.toByteArray());
        }
    }

    @ParameterizedTest
    @CsvSource({"TLSv1.2,false,8201", "TLSv1.2,true,8201", "TLSv1.3,false,8201", "TLSv1.3,true,8201",
        "TLSv1.2,false,16393", "TLSv1.2,true,16393", "TLSv1.3,false,16393", "TLSv1.3,true,16393"})
    void borrowedTransportSourceSurvivesCiphertextStallsAndChangingDrainBudgets(String protocol, boolean direct, int drainLimit) throws Exception {
        try (var pair = new Pair(protocol, 97, false, false)) {
            pair.handshake();
            byte[] expected = new byte[20003];
            new Random(812).nextBytes(expected);
            ByteBuffer storage = direct ? ByteBuffer.allocateDirect(expected.length + 9) : ByteBuffer.allocate(expected.length + 9);
            storage.position(5).put(expected).flip().position(5);
            ByteBuffer source = storage.asReadOnlyBuffer();
            var output = new TransportOutputBuffer(8201, drainLimit, () -> {});
            var progress = new TransportWriteProgress(() -> {});
            var write = output.asynchronousWriter().write(source, progress);
            var sink = new Channel(pair.server::writePlaintext);

            assertEquals(0, output.drainTo(sink, drainLimit));
            int packet = pair.server.encryptedOutputSize();
            assertTrue(packet > 8201);
            assertEquals(packet - 1, pair.server.drainEncryptedTo(new Channel(pair.client::receive), packet - 1));
            for (int budget : new int[]{1, 97, 16393, 8201}) {
                assertEquals(0, output.drainTo(sink, budget));
                assertFalse(progress.reached(1));
                assertFalse(write.isDone());
                assertEquals(5, source.position());
            }
            assertEquals(1, pair.server.drainEncryptedTo(new Channel(pair.client::receive), 1));
            assertEquals(1, output.drainTo(sink, 1));
            assertTrue(progress.reached(1));
            assertFalse(progress.reached(2));
            assertFalse(write.isDone());

            int[] budgets = {3, 16393, 1, 8201, 97};
            int[] turn = {0};
            pair.until(() -> write.isDone() && pair.clientReceived.size() == expected.length, () -> {
                int budget = budgets[turn[0]++ % budgets.length];
                assertTrue(output.drainTo(sink, budget) <= Math.min(budget, drainLimit));
            });
            write.get();
            assertTrue(progress.reached(expected.length));
            assertFalse(progress.reached(expected.length + 1));
            assertArrayEquals(expected, pair.clientReceived.toByteArray());
            assertEquals(5, source.position());
            assertEquals(expected.length + 5, source.limit());
            assertEquals(0, output.pendingBytes());
            assertEquals(0, pair.server.encryptedOutputSize(), "Completion must include the last ciphertext byte");
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
        final TlsOutputBufferPool serverOutputBuffers = new TlsOutputBufferPool();
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
            server = new TlsEngineDriver(serverEngine, tasks, ready::release,
                TlsEngineDriver.DEFAULT_MAX_BUFFER_SIZE, serverOutputBuffers);
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
