package io.muserver;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import scaffolding.Http1Client;

import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.*;
import static scaffolding.ClientUtils.*;

@Timeout(60)
class AsyncResponseFinalizationTest {
    @TempDir Path directory;

    static Stream<Arguments> protocolsAndOutcomes() {
        return Stream.of("plain", "TLSv1.2", "TLSv1.3").flatMap(protocol ->
            Stream.of(false, true).flatMap(h2 -> Stream.of(false, true)
                .map(reset -> Arguments.of(protocol, h2, reset))));
    }

    @ParameterizedTest @MethodSource("protocolsAndOutcomes")
    void finalEncoderOutputSuspendsUntilAcknowledgedAndRetainsTheExchange(
        String protocol, boolean h2, boolean reset
    ) throws Exception {
        var application = Executors.newSingleThreadExecutor();
        var internal = Executors.newSingleThreadExecutor();
        var timer = Executors.newSingleThreadScheduledExecutor();
        var closing = new AtomicInteger();
        Queue<Boolean> outcomes = new ConcurrentLinkedQueue<>();
        Queue<ChannelConnection> connections = new ConcurrentLinkedQueue<>();
        byte[] payload = new byte[(h2 ? 1 : 8) * 1024 * 1024];
        Arrays.fill(payload, (byte) 'x');
        boolean secure = !protocol.equals("plain");
        MuServerBuilder builder = MuServerBuilder.muServer().withThreadingMode(ThreadingMode.PLATFORM)
            .withHttp2Config(Http2ConfigBuilder.http2Enabled()).withTempDirectory(directory)
            .withHandlerExecutor(application).withContentEncoders(List.of(new ContentEncoder() {
                @Override public String contentCoding() { return "footer"; }
                @Override public boolean prepare(MuRequest request, MuResponse response) {
                    return request.uri().getPath().equals("/stall");
                }
                @Override public OutputStream wrapStream(MuRequest request, MuResponse response, OutputStream output) {
                    return new OutputStream() {
                        @Override public void write(int value) { }
                        @Override public void write(byte[] bytes, int offset, int length) { }
                        @Override public void flush() throws IOException { output.flush(); }
                        @Override public void close() throws IOException {
                            closing.incrementAndGet();
                            output.write(payload);
                            output.close();
                        }
                    };
                }
            }));
        builder.useChannelTransport = true;
        if (secure) builder.withHttpsPort(0); else builder.withHttpPort(0);
        builder.executionResourcesFactory = (supplied, mode) -> new ExecutionResources(application, false, internal, timer);
        builder.addHandler((request, response) -> {
            if (!request.uri().getPath().equals("/stall")) { response.write("healthy"); return true; }
            ChannelConnection connection = (ChannelConnection) ((BaseHttpConnection) request.connection()).transport;
            if (!h2) connection.socket.setSendBufferSize(4096);
            connections.add(connection);
            response.addCompletionListener(info -> outcomes.add(info.completedSuccessfully()));
            AsyncHandle handle = request.handleAsync();
            handle.write(ByteBuffer.allocate(0), handle::complete);
            return true;
        });
        List<Socket> sockets = new ArrayList<>();
        try (MuServer server = builder.start()) {
            try {
                H2ClientConnection h2Peer = null;
                if (h2) {
                    Socket socket = connect(server, protocol, true);
                    sockets.add(socket);
                    h2Peer = new H2ClientConnection(socket);
                    h2Peer.handshake(new Http2Settings(false, 4096, 100, 0, 16384, 32768));
                    for (int id = 1; id <= 5; id += 2) {
                        FieldBlock fields = RFCTestUtils.getHelloHeaders(secure ? "https" : "http", server.uri().getPort());
                        fields.set(":path", "/stall");
                        h2Peer.writeFrame(new Http2HeadersFrame(id, true, fields)).flush();
                    }
                    for (int i = 0; i < 3; i++) assertEquals("200", h2Peer.readLogicalFrame(Http2HeadersFrame.class).headers().get(":status"));
                } else {
                    for (int i = 0; i < 3; i++) {
                        Socket socket = connect(server, protocol, false);
                        sockets.add(socket);
                        socket.setReceiveBufferSize(4096);
                        socket.getOutputStream().write("GET /stall HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".getBytes(US_ASCII));
                    }
                }
                until(() -> closing.get() == 3);
                if (!h2) {
                    var outputField = ChannelConnection.class.getDeclaredField("output");
                    outputField.setAccessible(true);
                    var outputs = new ArrayList<TransportOutputBuffer>();
                    for (ChannelConnection connection : connections) outputs.add((TransportOutputBuffer) outputField.get(connection));
                    until(() -> outputs.size() == 3 && outputs.stream().allMatch(output -> output.pendingBytes() > 0));
                }
                internal.submit(() -> {}).get(3, TimeUnit.SECONDS);
                application.submit(() -> {}).get(3, TimeUnit.SECONDS);
                assertTrue(outcomes.isEmpty(), "Final output still belongs to each exchange");
                assertEquals(0, server.stats().completedRequests());
                var healthyClient = client.newBuilder().protocols(List.of(okhttp3.Protocol.HTTP_1_1)).build();
                try (var healthy = call(healthyClient, request(server.uri()).header("Connection", "close"))) {
                    assertEquals("healthy", healthy.body().string());
                } finally { healthyClient.connectionPool().evictAll(); }
                assertEquals(3, server.stats().activeRequests().size());
                if (h2) {
                    H2ClientConnection peer = java.util.Objects.requireNonNull(h2Peer);
                    if (reset) {
                        for (int id = 1; id <= 5; id += 2) peer.writeFrame(new Http2ResetStreamFrame(id, Http2ErrorCode.CANCEL.code()));
                        peer.flush();
                    } else {
                        peer.writeFrame(new Http2WindowUpdate(0, payload.length * 3));
                        for (int id = 1; id <= 5; id += 2) peer.writeFrame(new Http2WindowUpdate(id, payload.length));
                        peer.flush();
                        int[] received = new int[3];
                        int ended = 0;
                        while (ended < 3) {
                            var data = peer.readLogicalFrame(Http2DataFrame.class);
                            for (int offset = data.payloadOffset(); offset < data.payloadOffset() + data.payloadLength(); offset++) {
                                assertEquals((byte) 'x', data.payload()[offset]);
                            }
                            received[(data.streamId() - 1) / 2] += data.payloadLength();
                            if (data.endStream()) ended++;
                        }
                        assertArrayEquals(new int[]{payload.length, payload.length, payload.length}, received);
                    }
                    until(() -> outcomes.size() == 3);
                    byte[] ping = {1, 2, 3, 4, 5, 6, 7, 8};
                    peer.writeFrame(new Http2Ping(false, ping)).flush();
                    assertEquals(new Http2Ping(true, ping), peer.readLogicalFrame(Http2Ping.class));
                } else if (reset) {
                    for (Socket socket : sockets) socket.close();
                } else {
                    for (Socket socket : sockets) {
                        socket.setReceiveBufferSize(1024 * 1024);
                        var peer = new Http1Client(socket, socket.getInputStream(), socket.getOutputStream(), server.uri());
                        assertEquals("HTTP/1.1 200 OK", peer.readLine());
                        assertTrue(peer.readHeaders().contains(HeaderNames.TRANSFER_ENCODING, HeaderValues.CHUNKED, true));
                        int received = 0;
                        for (;;) {
                            int chunk = Integer.parseUnsignedInt(peer.readLine(), 16);
                            assertTrue(chunk <= payload.length);
                            if (chunk == 0) { assertEquals("", peer.readLine()); break; }
                            byte[] bytes = peer.in().readNBytes(chunk);
                            assertEquals(chunk, bytes.length);
                            for (byte value : bytes) assertEquals((byte) 'x', value);
                            received += chunk;
                            assertEquals("", peer.readLine());
                        }
                        assertEquals(payload.length, received);
                        assertEquals(-1, peer.in().read());
                    }
                }
                until(() -> outcomes.size() == 3);
                assertEquals(List.of(!reset, !reset, !reset), new ArrayList<>(outcomes));
                assertEquals(4, server.stats().completedRequests());
                try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
            } finally { for (Socket socket : sockets) socket.close(); }
        } finally { application.shutdownNow(); internal.shutdownNow(); timer.shutdownNow(); }
    }

    private static Socket connect(MuServer server, String protocol, boolean h2) throws Exception {
        boolean secure = !protocol.equals("plain");
        Socket socket = secure ? sslContextForTesting(veryTrustingTrustManager()).getSocketFactory()
            .createSocket("localhost", server.uri().getPort()) : new Socket("localhost", server.uri().getPort());
        socket.setSoTimeout(5000);
        if (secure) {
            SSLSocket tls = (SSLSocket) socket;
            tls.setEnabledProtocols(new String[]{protocol});
            if (h2) {
                var parameters = tls.getSSLParameters();
                parameters.setApplicationProtocols(new String[]{"h2"});
                tls.setSSLParameters(parameters);
            }
            tls.startHandshake();
            if (h2) assertEquals("h2", tls.getApplicationProtocol());
        }
        return socket;
    }

    private static void until(BooleanSupplier ready) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!ready.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "Final response progression timed out");
            Thread.sleep(1);
        }
    }
}
