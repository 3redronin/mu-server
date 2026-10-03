package io.muserver;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import scaffolding.SingleCarrier;

import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class CertificateCarrierStarvationTest {
    @TempDir Path temporary;

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void lookupAndContendingCallerLeaveCarrierAvailable(boolean failFirstLookup) throws Exception {
        SingleCarrier.run(CertificateCarrierStarvationTest.class, temporary.resolve("child.log"),
            Boolean.toString(failFirstLookup));
    }

    public static void main(String[] args) throws Exception {
        boolean failFirst = Boolean.parseBoolean(args[0]);
        HttpsConfig config = HttpsConfigBuilder.unsignedLocalhost().build3();
        assertTrue(config.certificates().isEmpty()); // A missing URI must not cache an empty result.
        var accepted = new CountDownLatch(1);
        var releaseHandshake = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var connections = new AtomicInteger();
        var serverDone = new CompletableFuture<Void>();
        var executor = SingleCarrier.executor();
        try (var listener = (SSLServerSocket) config.sslContext().getServerSocketFactory()
            .createServerSocket(0, 10, InetAddress.getLoopbackAddress())) {
            listener.setSoTimeout(10000);
            config.setHttpsUri(URI.create("https://localhost:" + listener.getLocalPort() + "/"));
            var peer = new Thread(() -> {
                try {
                    for (int attempt = 0; attempt < (failFirst ? 2 : 1); attempt++) {
                        try (var socket = (SSLSocket) listener.accept()) {
                            connections.incrementAndGet();
                            if (failFirst && attempt == 0) continue; // Normal peer disconnect during handshake.
                            socket.setSoTimeout(5000);
                            accepted.countDown();
                            assertTrue(releaseHandshake.await(10, TimeUnit.SECONDS), "Handshake was not released");
                            socket.startHandshake();
                        }
                    }
                    serverDone.complete(null);
                } catch (Throwable error) { serverDone.completeExceptionally(error); }
            }, "certificate-test-peer");
            peer.setDaemon(true);
            peer.start();
            if (failFirst) {
                assertTrue(executor.submit(config::certificates).get(5, TimeUnit.SECONDS).isEmpty());
            }
            var first = executor.submit(config::certificates);
            assertTrue(accepted.await(5, TimeUnit.SECONDS), "Certificate connection was not accepted");
            var second = executor.submit(() -> { secondStarted.countDown(); return config.certificates(); });
            assertTrue(secondStarted.await(3, TimeUnit.SECONDS), "Certificate lookup pinned the only carrier");
            assertEquals("progress", executor.submit(() -> "progress").get(3, TimeUnit.SECONDS));
            assertFalse(second.isDone(), "Second lookup must await the in-progress cache fill");
            releaseHandshake.countDown();
            var certificates = first.get(5, TimeUnit.SECONDS);
            assertFalse(certificates.isEmpty());
            assertSame(certificates, second.get(5, TimeUnit.SECONDS));
            assertSame(certificates, config.certificates());
            serverDone.get(5, TimeUnit.SECONDS);
            assertEquals(failFirst ? 2 : 1, connections.get());
        } finally {
            releaseHandshake.countDown();
            executor.shutdownNow();
        }
    }
}
