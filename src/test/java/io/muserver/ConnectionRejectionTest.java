package io.muserver;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import scaffolding.Http1Client;

import javax.net.ssl.SSLSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.*;
import static scaffolding.ClientUtils.sslContextForTesting;
import static scaffolding.ClientUtils.veryTrustingTrustManager;
import static scaffolding.MuAssert.assertEventually;

@Timeout(20)
class ConnectionRejectionTest {
    @ParameterizedTest @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void internalRejectionClosesSilentConnectionsAndAcceptorRecovers(boolean tls, boolean proxy) throws Exception {
        AtomicBoolean reject = new AtomicBoolean(true);
        AtomicInteger handled = new AtomicInteger();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60, TimeUnit.SECONDS,
            new SynchronousQueue<>()) {
            @Override public void execute(Runnable task) {
                if (reject.get()) throw new RejectedExecutionException("Controlled internal rejection");
                super.execute(task);
            }
        };
        MuServerBuilder builder = tls ? MuServerBuilder.httpsServer() : MuServerBuilder.httpServer();
        try (MuServer server = TestExecutionResources.configure(builder, executor, null, null, null)
            .withHAProxyProtocolEnabled(proxy)
            .addHandler((req, resp) -> { handled.incrementAndGet(); resp.write("ok"); return true; }).start()) {
            for (int i = 1; i <= 3; i++) {
                try (Socket socket = new Socket("localhost", server.uri().getPort())) {
                    socket.setSoTimeout(1000);
                    try { assertEquals(-1, socket.getInputStream().read(), "No HTTP response or handshake on rejection"); }
                    catch (SocketException reset) { /* An accepted socket can close with FIN or RST. */ }
                }
                assertEquals(i, server.stats().rejectedDueToOverload());
                assertNoPendingSockets(server);
                assertEquals(0, handled.get());
                assertTrue(server.activeConnections().isEmpty());
            }
            reject.set(false);
            assertEquals(200, exchange(server, proxy, tls, false));
            assertEquals(1, handled.get());
            assertEquals(3, server.stats().rejectedDueToOverload());
            assertEquals(0, server.stats().failedToConnect());
            assertEventually(() -> server.activeConnections().size(), equalTo(0));
            assertNoPendingSockets(server);
        } finally { executor.shutdownNow(); }
    }

    @ParameterizedTest @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void boundedHandlerExecutorStillReturns503AndRecovers(boolean h2, boolean proxy) throws Exception {
        ThreadPoolExecutor handlers = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger handled = new AtomicInteger();
        try {
            var blocker = handlers.submit(() -> { started.countDown(); release.await(); return null; });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            var queued = handlers.submit(() -> {});
            try (MuServer server = MuServerBuilder.httpServer().withHandlerExecutor(handlers)
                .withHAProxyProtocolEnabled(proxy).withHttp2Config(Http2ConfigBuilder.http2Enabled())
                .addHandler((req, resp) -> { handled.incrementAndGet(); resp.write("ok"); return true; }).start()) {
                assertEquals(503, exchange(server, proxy, false, h2));
                assertEquals(0, handled.get());
                assertEquals(1, server.stats().rejectedDueToOverload());
                release.countDown();
                blocker.get(5, TimeUnit.SECONDS);
                queued.get(5, TimeUnit.SECONDS);
                assertEquals(200, exchange(server, proxy, false, h2));
                assertEquals(1, handled.get());
                assertEquals(1, server.stats().rejectedDueToOverload());
            }
        } finally { release.countDown(); handlers.shutdownNow(); }
    }

    private static int exchange(MuServer server, boolean proxy, boolean tls, boolean h2) throws Exception {
        try (Socket raw = new Socket("localhost", server.uri().getPort())) {
            raw.setSoTimeout(3000);
            if (proxy) raw.getOutputStream().write("PROXY TCP4 192.0.2.1 198.51.100.2 12345 443\r\n".getBytes(StandardCharsets.US_ASCII));
            Socket transport = raw;
            if (tls) {
                SSLSocket secure = (SSLSocket) sslContextForTesting(veryTrustingTrustManager).getSocketFactory()
                    .createSocket(raw, "localhost", server.uri().getPort(), true);
                secure.setSoTimeout(3000);
                secure.startHandshake();
                transport = secure;
            }
            if (h2) {
                try (var connection = new okhttp3.internal.http2.Http2Connection
                    .Builder(true, okhttp3.internal.concurrent.TaskRunner.INSTANCE).socket(transport).build()) {
                    connection.start();
                    var stream = connection.newStream(List.of(
                        new okhttp3.internal.http2.Header(":method", "GET"),
                        new okhttp3.internal.http2.Header(":path", "/"),
                        new okhttp3.internal.http2.Header(":scheme", "http"),
                        new okhttp3.internal.http2.Header(":authority", "localhost")), false);
                    stream.readTimeout().timeout(3, TimeUnit.SECONDS);
                    int status = Integer.parseInt(stream.takeHeaders().get(":status"));
                    String body = okio.Okio.buffer(stream.getSource()).readUtf8();
                    assertEquals(status == 200 ? "ok" : "503 Service Unavailable", body);
                    return status;
                }
            }
            try (Http1Client client = new Http1Client(transport, transport.getInputStream(), transport.getOutputStream(), server.uri())) {
                client.writeRequestLine(Method.GET, "/").writeHeader("Connection", "close").endHeaders().flush();
                int status = Integer.parseInt(client.readLine().split(" ")[1]);
                assertEquals(status == 200 ? "ok" : "503 Service Unavailable", client.readBody(client.readHeaders()));
                return status;
            }
        }
    }

    private static void assertNoPendingSockets(MuServer server) throws Exception {
        var acceptors = Mu3ServerImpl.class.getDeclaredField("acceptors");
        acceptors.setAccessible(true);
        for (Object acceptor : (List<?>) acceptors.get(server)) {
            var lockField = ConnectionAcceptor.class.getDeclaredField("lifecycleLock");
            var acceptedField = ConnectionAcceptor.class.getDeclaredField("acceptedSockets");
            var pendingField = ConnectionAcceptor.class.getDeclaredField("pendingPreambles");
            lockField.setAccessible(true); acceptedField.setAccessible(true); pendingField.setAccessible(true);
            ReentrantLock lock = (ReentrantLock) lockField.get(acceptor);
            lock.lock();
            try {
                assertTrue(((Collection<?>) acceptedField.get(acceptor)).isEmpty());
                assertTrue(((Map<?, ?>) pendingField.get(acceptor)).isEmpty());
            } finally { lock.unlock(); }
        }
    }
}
