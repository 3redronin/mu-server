package io.muserver;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.*;
import static scaffolding.ClientUtils.*;

@Timeout(30)
class AsyncResponseChannelTest {
    @TempDir Path directory;

    static Stream<Arguments> encodingsAndTransports() {
        return Stream.of("plain", "TLSv1.2", "TLSv1.3").flatMap(protocol ->
            Stream.of("identity", "gzip", "burst").map(encoding -> Arguments.of(protocol, encoding)));
    }

    @ParameterizedTest @MethodSource("encodingsAndTransports")
    void unreadResponsesDoNotOccupyTheOnlyInternalWorker(String protocol, String encoding) throws Exception {
        var application = Executors.newSingleThreadExecutor();
        var internal = Executors.newSingleThreadExecutor();
        var timer = Executors.newSingleThreadScheduledExecutor();
        Queue<ChannelConnection> connections = new ConcurrentLinkedQueue<>();
        Queue<Future<?>> writes = new ConcurrentLinkedQueue<>();
        Queue<Boolean> outcomes = new ConcurrentLinkedQueue<>();
        List<Socket> peers = new ArrayList<>();
        byte[] payload = new byte[8 * 1024 * 1024];
        new java.util.Random(123).nextBytes(payload); // Gzip must still reach real socket backpressure.
        boolean secure = !protocol.equals("plain");
        MuServerBuilder builder = MuServerBuilder.muServer().withThreadingMode(ThreadingMode.PLATFORM)
            .withHttp2Config(Http2ConfigBuilder.http2Disabled()).withTempDirectory(directory)
            .withHandlerExecutor(application);
        builder.useChannelTransport = true;
        if (secure) builder.withHttpsPort(0); else builder.withHttpPort(0);
        builder.executionResourcesFactory = (supplied, mode) -> new ExecutionResources(application, false, internal, timer);
        if (encoding.equals("burst")) builder.withContentEncoders(List.of(burstEncoder(payload)));
        builder.addHandler((req, res) -> {
            if (!req.uri().getPath().equals("/stall")) { res.write("healthy"); return true; }
            var transport = (ChannelConnection) ((BaseHttpConnection) req.connection()).transport;
            transport.socket.setSendBufferSize(4096);
            connections.add(transport);
            res.contentType("text/plain");
            res.addCompletionListener(info -> outcomes.add(info.completedSuccessfully()));
            AsyncHandle handle = req.handleAsync();
            writes.add(handle.write(ByteBuffer.wrap(encoding.equals("burst") ? new byte[]{1} : payload).asReadOnlyBuffer()));
            return true;
        });
        try (MuServer server = builder.start()) {
            try {
                for (int i = 0; i < 3; i++) {
                    Socket peer = secure ? sslContextForTesting(veryTrustingTrustManager()).getSocketFactory()
                        .createSocket("localhost", server.uri().getPort()) : new Socket("localhost", server.uri().getPort());
                    peers.add(peer);
                    peer.setReceiveBufferSize(4096);
                    if (secure) {
                        ((SSLSocket) peer).setEnabledProtocols(new String[]{protocol});
                        ((SSLSocket) peer).startHandshake();
                    }
                    peer.getOutputStream().write(("GET /stall HTTP/1.1\r\nHost: localhost\r\nAccept-Encoding: "
                        + encoding + "\r\n\r\n").getBytes(US_ASCII));
                }
                until(() -> writes.size() == 3 && connections.size() == 3);
                var outputField = ChannelConnection.class.getDeclaredField("output");
                outputField.setAccessible(true);
                var outputs = new ArrayList<TransportOutputBuffer>();
                for (ChannelConnection connection : connections) outputs.add((TransportOutputBuffer) outputField.get(connection));
                until(() -> outputs.stream().allMatch(output -> output.pendingBytes() > 0));
                internal.submit(() -> {}).get(3, TimeUnit.SECONDS);
                application.submit(() -> {}).get(3, TimeUnit.SECONDS);
                try (var healthy = call(request(server.uri()).header("Connection", "close"))) {
                    assertEquals("healthy", healthy.body().string());
                }
                for (Future<?> write : writes) {
                    assertFalse(write.isDone());
                    assertTrue(write.cancel(false));
                    assertThrows(CancellationException.class, () -> write.get(3, TimeUnit.SECONDS));
                }
                until(() -> outcomes.size() == 3 && server.stats().activeConnections() == 0);
                assertEquals(List.of(false, false, false), new ArrayList<>(outcomes));
                assertEquals(4, server.stats().completedConnections());
                assertEquals(4, server.stats().completedRequests());
                assertTrue(outputs.stream().allMatch(output -> output.pendingBytes() == 0));
                try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
            } finally { for (Socket peer : peers) peer.close(); }
        } finally { application.shutdownNow(); internal.shutdownNow(); timer.shutdownNow(); }
    }

    private static ContentEncoder burstEncoder(byte[] payload) {
        return new ContentEncoder() {
            @Override public String contentCoding() { return "burst"; }
            @Override public boolean prepare(MuRequest request, MuResponse response) {
                return request.uri().getPath().equals("/stall");
            }
            @Override public OutputStream wrapStream(MuRequest request, MuResponse response, OutputStream output) {
                return new OutputStream() {
                    @Override public void write(int value) throws IOException { output.write(payload); }
                    @Override public void write(byte[] bytes, int offset, int length) throws IOException { output.write(payload); }
                    @Override public void flush() throws IOException { output.flush(); }
                    @Override public void close() throws IOException { output.close(); }
                };
            }
        };
    }

    private static void until(BooleanSupplier ready) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!ready.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "Channel response progression timed out");
            Thread.sleep(1);
        }
    }
}
