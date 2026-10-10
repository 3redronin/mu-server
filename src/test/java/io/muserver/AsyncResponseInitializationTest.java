package io.muserver;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.*;
import static scaffolding.ClientUtils.sslContextForTesting;
import static scaffolding.ClientUtils.veryTrustingTrustManager;

@Timeout(15)
class AsyncResponseInitializationTest {
    static Stream<Arguments> bodylessEncoders() {
        return Stream.of(false, true).flatMap(channel -> Stream.of(false, true).flatMap(tls ->
            Stream.of(false, true).flatMap(head -> Stream.of("none", "initialize", "write", "close")
                .map(failure -> Arguments.of(channel, tls, head, failure)))));
    }

    @ParameterizedTest @MethodSource("bodylessEncoders")
    void headersDrainBeforeEncoderInitializationAndCompletionStillWaitsForTheEncoder(
        boolean channel, boolean tls, boolean head, String failure
    ) throws Exception {
        var initializing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var prepared = new AtomicInteger();
        var closed = new AtomicInteger();
        var completed = new CompletableFuture<ResponseInfo>();
        var encoder = new ContentEncoder() {
            @Override public String contentCoding() { return "tracked"; }
            @Override public boolean prepare(MuRequest request, MuResponse response) {
                prepared.incrementAndGet(); response.headers().set(HeaderNames.CONTENT_ENCODING, "tracked"); return true;
            }
            @Override public OutputStream wrapStream(MuRequest request, MuResponse response, OutputStream output) throws IOException {
                initializing.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Encoder initialization was not released");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); throw new IOException(interrupted);
                }
                if (failure.equals("initialize")) throw new IOException("Encoder initialization failed");
                return new FilterOutputStream(output) {
                    @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                        if (failure.equals("write")) throw new IOException("Encoder write failed");
                        out.write(bytes, offset, length);
                    }
                    @Override public void close() throws IOException {
                        try { super.close(); } finally { closed.incrementAndGet(); }
                        if (failure.equals("close")) throw new IOException("Encoder close failed");
                    }
                };
            }
        };
        var builder = MuServerBuilder.muServer().withHttp2Config(Http2ConfigBuilder.http2Disabled())
            .withContentEncoders(List.of(encoder)).addResponseCompleteListener(completed::complete)
            .addHandler((request, response) -> {
                response.status(head ? 200 : 304);
                var async = request.handleAsync();
                async.write(ByteBuffer.wrap(new byte[]{1}), async::complete);
                return true;
            });
        builder.useChannelTransport = channel;
        if (tls) builder.withHttpsPort(0); else builder.withHttpPort(0);
        MuServer server = builder.start();
        try {
            try (Socket peer = tls ? sslContextForTesting(veryTrustingTrustManager()).getSocketFactory()
                .createSocket("localhost", server.uri().getPort()) : new Socket("localhost", server.uri().getPort())) {
                peer.setSoTimeout(5000);
                peer.getOutputStream().write(((head ? "HEAD" : "GET")
                    + " / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n").getBytes(US_ASCII));
                var headers = new ByteArrayOutputStream();
                int matched = 0;
                byte[] end = {'\r', '\n', '\r', '\n'};
                while (matched < end.length) {
                    int next = peer.getInputStream().read();
                    assertTrue(next >= 0 && headers.size() < 8192);
                    headers.write(next);
                    matched = next == end[matched] ? matched + 1 : next == '\r' ? 1 : 0;
                }
                String received = headers.toString(US_ASCII);
                assertTrue(received.startsWith("HTTP/1.1 " + (head ? 200 : 304)), received);
                assertTrue(received.toLowerCase(java.util.Locale.ROOT).contains("content-encoding: tracked"), received);
                assertTrue(initializing.await(3, TimeUnit.SECONDS));
                assertFalse(completed.isDone());
                // A legacy SSLSocket close can itself wait for the server to read close_notify.
                // Release the encoder before initiating that separate shutdown handshake.
                release.countDown();
            }
            assertEquals(failure.equals("none"), completed.get(5, TimeUnit.SECONDS).completedSuccessfully());
            assertEquals(1, prepared.get());
            if (failure.equals("none") || failure.equals("close")) assertEquals(1, closed.get());
        } finally { release.countDown(); server.stop(); }
    }
}
