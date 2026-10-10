package io.muserver;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class AsyncResponseCompletionDispatchTest {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void peerResetCannotRunEncoderCleanupInsideTheHttp2Coordinator(boolean channel) throws Exception {
        var closing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var submitted = new CompletableFuture<Void>();
        var application = new DirectApplication(submitted);
        var builder = MuServerBuilder.httpServer().withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .withThreadingMode(ThreadingMode.PLATFORM).withHandlerExecutor(application)
            .withContentEncoders(List.of(new ContentEncoder() {
                @Override public String contentCoding() { return "tracked"; }
                @Override public boolean prepare(MuRequest request, MuResponse response) { return true; }
                @Override public OutputStream wrapStream(MuRequest request, MuResponse response, OutputStream output) {
                    return new FilterOutputStream(output) {
                        @Override public void close() throws IOException {
                            closing.countDown();
                            try {
                                if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("Encoder close not released");
                            } catch (InterruptedException failure) {
                                Thread.currentThread().interrupt(); throw new IOException(failure);
                            }
                            super.close();
                        }
                    };
                }
            })).addHandler((request, response) -> {
                OutputStream output = response.outputStream(0);
                output.write(42);
                output.flush();
                request.handleAsync();
                return true;
            });
        builder.useChannelTransport = channel;
        try (MuServer server = builder.start(); var client = new H2Client(); var peer = client.connectClearText(server)) {
            try {
                peer.socket().setSoTimeout(2000);
                peer.handshake().writeFrame(new Http2HeadersFrame(1, true,
                    RFCTestUtils.getHelloHeaders("http", server.uri().getPort()))).flush();
                assertFalse(peer.readLogicalFrame(Http2HeadersFrame.class).endStream());
                assertFalse(peer.readLogicalFrame(Http2DataFrame.class).endStream());
                submitted.get(3, TimeUnit.SECONDS);
                byte[] ping = {1, 2, 3, 4, 5, 6, 7, 8};
                peer.writeFrame(new Http2ResetStreamFrame(1, Http2ErrorCode.CANCEL.code()))
                    .writeFrame(new Http2Ping(false, ping)).flush();
                assertTrue(closing.await(3, TimeUnit.SECONDS));
                assertEquals(0, server.stats().completedRequests());
                assertEquals(new Http2Ping(true, ping), peer.readLogicalFrame(Http2Ping.class));
                release.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (server.stats().completedRequests() != 1) {
                    assertTrue(System.nanoTime() < deadline);
                    Thread.sleep(1);
                }
            } finally { release.countDown(); }
        } finally { release.countDown(); application.shutdownNow(); }
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void completionOfAnUnconsumedUploadCannotRunListenersInsideTheHttp2Reader(boolean channel) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var submitted = new CompletableFuture<Void>();
        var notified = new CompletableFuture<Void>();
        var application = new DirectApplication(submitted);
        var builder = MuServerBuilder.httpServer().withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .withThreadingMode(ThreadingMode.PLATFORM).withHandlerExecutor(application)
            .addHandler((request, response) -> {
                response.status(204);
                response.addCompletionListener(info -> {
                    entered.countDown();
                    try {
                        assertTrue(release.await(10, TimeUnit.SECONDS));
                        notified.complete(null);
                    } catch (Throwable failure) { notified.completeExceptionally(failure); }
                });
                return true;
            });
        builder.useChannelTransport = channel;
        try (MuServer server = builder.start(); var client = new H2Client(); var peer = client.connectClearText(server)) {
            try {
                peer.socket().setSoTimeout(2000);
                FieldBlock headers = RFCTestUtils.getHelloHeaders("http", server.uri().getPort());
                headers.set(":method", "POST");
                headers.set(HeaderNames.CONTENT_LENGTH, 1);
                peer.handshake().writeFrame(new Http2HeadersFrame(1, false, headers)).flush();
                assertTrue(peer.readLogicalFrame(Http2HeadersFrame.class).endStream());
                submitted.get(3, TimeUnit.SECONDS);
                byte[] ping = {1, 2, 3, 4, 5, 6, 7, 8};
                peer.writeFrame(new Http2DataFrame(1, true, new byte[]{42}, 0, 1))
                    .writeFrame(new Http2Ping(false, ping)).flush();
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                assertEquals(new Http2Ping(true, ping), RFCTestUtils.readIgnoringWindowUpdates(peer, Http2Ping.class));
                release.countDown();
                notified.get(3, TimeUnit.SECONDS);
                assertEquals(1, server.stats().completedRequests());
            } finally { release.countDown(); }
        } finally { release.countDown(); application.shutdownNow(); }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void headerRejectionCannotRunListenersInsideTheHttp2Reader(boolean channel) throws Exception {
        var entered = new CompletableFuture<Integer>();
        var release = new CountDownLatch(1);
        var notified = new CompletableFuture<Void>();
        var application = new DirectApplication(new CompletableFuture<>());
        var builder = MuServerBuilder.httpServer().withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .withMaxHeadersSize(1024).withThreadingMode(ThreadingMode.PLATFORM).withHandlerExecutor(application)
            .addRequestRejectListener(info -> {
                entered.complete(info.status());
                try {
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                    notified.complete(null);
                } catch (Throwable failure) { notified.completeExceptionally(failure); }
            }).addHandler((request, response) -> { fail("Oversized request reached the handler"); return true; });
        builder.useChannelTransport = channel;
        try (MuServer server = builder.start(); var client = new H2Client(); var peer = client.connectClearText(server)) {
            try {
                peer.socket().setSoTimeout(2000);
                FieldBlock headers = RFCTestUtils.getHelloHeaders("http", server.uri().getPort());
                headers.set("x-large", "x".repeat(2048));
                byte[] ping = {1, 2, 3, 4, 5, 6, 7, 8};
                peer.handshake().writeFrame(new Http2HeadersFrame(1, true, headers))
                    .writeFrame(new Http2Ping(false, ping)).flush();
                assertEquals(431, entered.get(3, TimeUnit.SECONDS));
                LogicalHttp2Frame frame;
                do {
                    frame = peer.readLogicalFrame();
                    assertTrue(frame instanceof Http2HeadersFrame || frame instanceof Http2DataFrame || frame instanceof Http2Ping);
                } while (!(frame instanceof Http2Ping));
                assertEquals(new Http2Ping(true, ping), frame);
                release.countDown();
                notified.get(3, TimeUnit.SECONDS);
            } finally { release.countDown(); }
        } finally { release.countDown(); application.shutdownNow(); }
    }

    private static final class DirectApplication extends AbstractExecutorService {
        private final CompletableFuture<Void> submitted;
        private volatile boolean shutdown;
        private DirectApplication(CompletableFuture<Void> submitted) { this.submitted = submitted; }
        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() { shutdown = true; return List.of(); }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return shutdown; }
        @Override public void execute(Runnable command) {
            if (shutdown) throw new RejectedExecutionException();
            command.run();
            submitted.complete(null);
        }
    }

}
