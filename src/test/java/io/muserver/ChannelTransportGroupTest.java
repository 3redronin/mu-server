package io.muserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.*;
import static scaffolding.ClientUtils.sslContextForTesting;
import static scaffolding.ClientUtils.veryTrustingTrustManager;

@Timeout(30)
class ChannelTransportGroupTest {
    @Test
    void transportThreadConfigurationIsExplicitOrBoundedAutomatic() {
        var builder = MuServerBuilder.muServer();
        assertEquals(0, builder.nioThreads());
        assertTrue(builder.channelThreads() >= 1 && builder.channelThreads() <= 4);
        assertSame(builder, builder.withNioThreads(3));
        assertEquals(3, builder.nioThreads());
        assertEquals(3, builder.channelThreads());
        assertThrows(IllegalArgumentException.class, () -> builder.withNioThreads(-1));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void connectionsKeepTheirOwnersAndShareTlsTaskCapacity(boolean secure) throws Exception {
        var handled = new LinkedBlockingQueue<ChannelTransportLoop>();
        var builder = MuServerBuilder.muServer().withNioThreads(3).withHttp2Config(Http2ConfigBuilder.http2Disabled());
        if (secure) builder.withHttpsPort(0); else builder.withHttpPort(0);
        var sockets = new ArrayList<Socket>();
        var owners = new ArrayList<ChannelTransportLoop>();
        try (MuServer server = builder.addHandler((req, res) -> {
            handled.add(owner((BaseHttpConnection) req.connection()));
            res.status(204);
            return true;
        }).start()) {
            try {
                for (int i = 0; i < 9; i++) {
                    Socket socket = secure ? sslContextForTesting(veryTrustingTrustManager()).getSocketFactory()
                        .createSocket("localhost", server.uri().getPort()) : new Socket("localhost", server.uri().getPort());
                    sockets.add(socket);
                    socket.setSoTimeout(5000);
                    request(socket);
                    ChannelTransportLoop owner = handled.poll(5, TimeUnit.SECONDS);
                    assertNotNull(owner);
                    owners.add(owner);
                }
                assertEquals(3, new HashSet<>(owners).size());
                for (int i = 0; i < sockets.size(); i++) {
                    request(sockets.get(i));
                    assertSame(owners.get(i), handled.poll(5, TimeUnit.SECONDS));
                    assertSame(owners.get(0).tlsTasks, owners.get(i).tlsTasks,
                        "Adding I/O owners must not multiply TLS task workers or queue capacity");
                }
            } finally {
                for (Socket socket : sockets) socket.close();
            }
            assertTrue(server.stop(5, TimeUnit.SECONDS));
            assertEquals(9, server.stats().completedConnections());
            assertEquals(18, server.stats().completedRequests());
            assertOwnersStopped(owners);
        }
    }

    @Test
    void selectorFailureStopsEveryOwnerButRetainsRunningHandlers() throws Exception {
        var handled = new LinkedBlockingQueue<ChannelTransportLoop>();
        var release = new CountDownLatch(1);
        var sockets = new ArrayList<Socket>();
        var owners = new ArrayList<ChannelTransportLoop>();
        try (MuServer server = MuServerBuilder.httpServer().withNioThreads(3).addHandler((req, res) -> {
            handled.add(owner((BaseHttpConnection) req.connection()));
            release.await();
            return true;
        }).start()) {
            try {
                for (int i = 0; i < 3; i++) {
                    Socket socket = new Socket("localhost", server.uri().getPort());
                    sockets.add(socket);
                    socket.setSoTimeout(5000);
                    socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(US_ASCII));
                    ChannelTransportLoop owner = handled.poll(5, TimeUnit.SECONDS);
                    assertNotNull(owner);
                    owners.add(owner);
                }
                assertEquals(3, new HashSet<>(owners).size());
                owners.get(0).selector.close();
                for (Socket socket : sockets) assertEquals(-1, socket.getInputStream().read());
                assertEquals(0, server.stats().completedRequests());
                assertEquals(3, server.stats().activeConnections());
                release.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (server.stats().completedConnections() != 3) {
                    assertTrue(System.nanoTime() < deadline);
                    Thread.sleep(2);
                }
                assertEquals(3, server.stats().completedRequests());
                assertOwnersStopped(owners);
            } finally {
                release.countDown();
                for (Socket socket : sockets) socket.close();
            }
        }
    }

    private static ChannelTransportLoop owner(BaseHttpConnection connection) throws Exception {
        var field = ChannelConnection.class.getDeclaredField("loop");
        field.setAccessible(true);
        return (ChannelTransportLoop) field.get(connection.transport);
    }

    private static void assertOwnersStopped(Iterable<ChannelTransportLoop> owners) throws Exception {
        var field = ChannelTransportLoop.class.getDeclaredField("thread");
        field.setAccessible(true);
        for (ChannelTransportLoop owner : owners) {
            Thread thread = (Thread) field.get(owner);
            thread.join(2000);
            assertFalse(thread.isAlive());
            assertTrue(owner.tlsTasks.isShutdown());
        }
    }

    private static void request(Socket socket) throws Exception {
        socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(US_ASCII));
        var headers = new ByteArrayOutputStream();
        while (!headers.toString(US_ASCII).endsWith("\r\n\r\n")) {
            int value = socket.getInputStream().read();
            assertTrue(value >= 0 && headers.size() < 8192);
            headers.write(value);
        }
        assertTrue(headers.toString(US_ASCII).startsWith("HTTP/1.1 204"));
    }
}
