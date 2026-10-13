package io.muserver;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.Socket;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class ResponseFatalCleanupTest {
    @ParameterizedTest @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void fatalEncoderCloseRetiresAndNotifiesOnceBeforeEscapingTheWorker(boolean channel, boolean h2) throws Exception {
        var fatal = new CompletableFuture<Throwable>();
        var completed = new CompletableFuture<ResponseInfo>();
        var closed = new AtomicInteger();
        var connection = new CompletableFuture<HttpConnection>();
        ThreadFactory threads = task -> {
            var thread = new Thread(task, "fatal-cleanup-worker");
            thread.setUncaughtExceptionHandler((worker, error) -> fatal.complete(error));
            return thread;
        };
        var application = Executors.newSingleThreadExecutor(threads);
        var internal = Executors.newCachedThreadPool(threads);
        var timer = Executors.newSingleThreadScheduledExecutor();
        var builder = MuServerBuilder.httpServer().withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .withHandlerExecutor(application).withThreadingMode(ThreadingMode.PLATFORM)
            .withContentEncoders(List.of(new ContentEncoder() {
                @Override public String contentCoding() { return "fatal"; }
                @Override public boolean prepare(MuRequest request, MuResponse response) { return true; }
                @Override public OutputStream wrapStream(MuRequest request, MuResponse response, OutputStream output) {
                    return new FilterOutputStream(output) {
                        @Override public void close() throws IOException {
                            closed.incrementAndGet();
                            throw new InternalError("Controlled fatal encoder close");
                        }
                    };
                }
            })).addHandler((request, response) -> {
                if (request.uri().getPath().equals("/healthy")) { response.status(204); return true; }
                connection.complete(request.connection());
                response.addCompletionListener(completed::complete);
                response.outputStream(0).write(42);
                return true;
            });
        builder.useChannelTransport = channel;
        builder.executionResourcesFactory = (supplied, mode) -> new ExecutionResources(application, false, internal, timer);
        MuServer server = builder.start();
        try (var client = new H2Client();
             var peer = h2 ? client.connectClearText(server) : null;
             var socket = h2 ? null : new Socket("localhost", server.uri().getPort())) {
            if (peer != null) {
                peer.socket().setSoTimeout(2000);
                peer.handshake().writeFrame(new Http2HeadersFrame(1, true,
                    RFCTestUtils.getHelloHeaders("http", server.uri().getPort()))).flush();
                assertFalse(peer.readLogicalFrame(Http2HeadersFrame.class).endStream());
                assertFalse(peer.readLogicalFrame(Http2DataFrame.class).endStream());
            } else if (socket != null) {
                socket.setSoTimeout(2000);
                socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            }
            assertInstanceOf(InternalError.class, fatal.get(3, TimeUnit.SECONDS));
            assertEquals(1, server.stats().completedRequests(), "Fatal unwinding cannot retire the same exchange twice");
            assertEquals(1, connection.get().completedRequests());
            assertEquals(1, closed.get());
            assertFalse(completed.get(3, TimeUnit.SECONDS).completedSuccessfully());
            if (peer != null) {
                assertEquals(Http2ErrorCode.INTERNAL_ERROR, peer.readLogicalFrame(Http2ResetStreamFrame.class).errorCodeEnum());
                FieldBlock headers = RFCTestUtils.getHelloHeaders("http", server.uri().getPort());
                headers.set(":path", "/healthy");
                peer.writeFrame(new Http2HeadersFrame(3, true, headers)).flush();
                assertEquals("204", peer.readLogicalFrame(Http2HeadersFrame.class).headers().get(":status"));
            } else if (socket != null) {
                socket.getInputStream().readAllBytes(); // EOF must still follow fatal cleanup.
            }
            try (var healthy = scaffolding.ClientUtils.call(scaffolding.ClientUtils.request(server.uri().resolve("/healthy")))) {
                assertEquals(204, healthy.code(), "A failed application must not stop the shared transport listener");
            }
        } finally {
            server.stop(0, TimeUnit.MILLISECONDS);
            application.shutdownNow(); internal.shutdownNow(); timer.shutdownNow();
        }
    }
}
