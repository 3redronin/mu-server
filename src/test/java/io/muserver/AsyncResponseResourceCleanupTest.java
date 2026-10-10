package io.muserver;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class AsyncResponseResourceCleanupTest {
    @TempDir Path directory;

    static Stream<Arguments> protocolsAndOutcomes() {
        return Stream.of(false, true).flatMap(channel -> Stream.of(false, true).flatMap(h2 ->
            Stream.of("success", "failure", "failure-and-close-error", "failure-and-writer-error").map(outcome -> Arguments.of(channel, h2, outcome))));
    }

    @ParameterizedTest @MethodSource("protocolsAndOutcomes")
    void completionReleasesParsedUploadsAndOpenedEncoders(boolean channel, boolean h2, String outcome) throws Exception {
        boolean fail = !outcome.equals("success");
        boolean bufferedWriter = outcome.equals("failure-and-writer-error");
        var closed = new AtomicInteger();
        var completed = new CompletableFuture<ResponseInfo>();
        var encoder = new ContentEncoder() {
            @Override public String contentCoding() { return "tracked"; }
            @Override public boolean prepare(MuRequest request, MuResponse response) { return true; }
            @Override public OutputStream wrapStream(MuRequest request, MuResponse response, OutputStream output) {
                return new FilterOutputStream(output) {
                    @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                        if (bufferedWriter) throw new IllegalStateException("Buffered writer flush failed");
                        out.write(bytes, offset, length);
                    }
                    @Override public void close() throws IOException {
                        closed.incrementAndGet();
                        out.write("encoder-footer".getBytes(US_ASCII));
                        super.close();
                        if (outcome.equals("failure-and-close-error") || bufferedWriter) throw new IOException("Encoder close failed");
                    }
                };
            }
        };
        MuServerBuilder builder = MuServerBuilder.httpServer().withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .withContentEncoders(List.of(encoder)).withTempDirectory(directory)
            .addHandler((request, response) -> {
                assertEquals("hello", request.form().uploadedFile("upload").asString());
                try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
                response.addCompletionListener(info -> {
                    try (var files = Files.list(directory)) {
                        assertEquals(1, closed.get(), "Encoder cleanup must precede completion callbacks");
                        assertEquals(0, files.count(), "Upload cleanup must precede completion callbacks");
                        completed.complete(info);
                    } catch (Throwable failure) { completed.completeExceptionally(failure); }
                });
                if (bufferedWriter) {
                    response.writer().write("Still buffered when the response fails");
                    request.handleAsync().complete(new IOException("Failure with pending writer output"));
                    return true;
                }
                AsyncHandle handle = request.handleAsync();
                handle.write(ByteBuffer.wrap(new byte[]{42}), error -> {
                    if (error != null) handle.complete(error);
                    else if (fail) handle.complete(new IOException("deliberate async failure after starting output"));
                    else handle.complete();
                });
                return true;
            });
        builder.useChannelTransport = channel;
        byte[] body = ("--boundary\r\nContent-Disposition: form-data; name=\"upload\"; filename=\"hello.txt\"\r\n"
            + "Content-Type: text/plain\r\n\r\nhello\r\n--boundary--\r\n").getBytes(US_ASCII);
        try (MuServer server = builder.start(); Socket socket = new Socket("localhost", server.uri().getPort())) {
            socket.setSoTimeout(5000);
            if (h2) {
                try (var peer = new H2ClientConnection(socket)) {
                    peer.handshake();
                    FieldBlock headers = RFCTestUtils.getHelloHeaders("http", server.uri().getPort());
                    headers.set(":method", "POST");
                    headers.set(HeaderNames.CONTENT_TYPE, "multipart/form-data; boundary=boundary");
                    headers.set(HeaderNames.CONTENT_LENGTH, body.length);
                    peer.writeFrame(new Http2HeadersFrame(1, false, headers))
                        .writeFrame(new Http2DataFrame(1, true, body, 0, body.length)).flush();
                    completed.get(5, TimeUnit.SECONDS);
                    var content = new ByteArrayOutputStream();
                    LogicalHttp2Frame frame;
                    do {
                        frame = peer.readLogicalFrame();
                        if (frame instanceof Http2DataFrame) {
                            Http2DataFrame data = (Http2DataFrame) frame;
                            content.write(data.payload(), data.payloadOffset(), data.payloadLength());
                        }
                    } while (!frame.endStream() && !(frame instanceof Http2ResetStreamFrame));
                    assertEquals(fail, frame instanceof Http2ResetStreamFrame);
                    assertEquals(bufferedWriter ? "" : fail ? "*" : "*encoder-footer", content.toString(US_ASCII));
                }
            } else {
                socket.getOutputStream().write(("POST / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n"
                    + "Content-Type: multipart/form-data; boundary=boundary\r\nContent-Length: " + body.length
                    + "\r\n\r\n").getBytes(US_ASCII));
                socket.getOutputStream().write(body);
                completed.get(5, TimeUnit.SECONDS);
                String wire = new String(socket.getInputStream().readAllBytes(), US_ASCII);
                assertEquals(!fail, wire.endsWith("0\r\n\r\n"), wire);
                assertEquals(!fail, wire.contains("encoder-footer"), wire);
            }
            assertEquals(!fail, completed.get().completedSuccessfully());
            long files;
            try (var remaining = Files.list(directory)) { files = remaining.count(); }
            long remainingFiles = files;
            assertAll(() -> assertEquals(1, closed.get(), "The opened encoder must be closed exactly once"),
                () -> assertEquals(0, remainingFiles, "Parsed uploads must be removed before completion callbacks"));
        }
    }
}
