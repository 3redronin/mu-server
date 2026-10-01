package io.muserver;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import scaffolding.Http1Client;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static io.muserver.RFCTestUtils.getHelloHeaders;
import static io.muserver.RFCTestUtils.readIgnoringWindowUpdates;
import static org.junit.jupiter.api.Assertions.*;
import static scaffolding.MuAssert.stopAndCheck;
import static scaffolding.ServerUtils.httpsServerForTest;

@Timeout(15)
class ResponseWriteContractTest {
    static Stream<Arguments> completeResponses() {
        return Stream.of("http", "https", "h2", "h2c").flatMap(protocol ->
            Stream.of("empty", "small", "boundary", "large", "encoded", "head", "bodyless")
                .map(mode -> Arguments.of(protocol, mode)));
    }

    @ParameterizedTest
    @MethodSource("completeResponses")
    void completedWriteRejectsFurtherOutputWithoutChangingHeaders(String protocol, String mode) throws Exception {
        String text = "empty".equals(mode) ? "" : "x".repeat("boundary".equals(mode) ? 8192 : "large".equals(mode) ? 8193 : 5);
        var checked = new CompletableFuture<Void>();
        var builder = httpsServerForTest("h2c".equals(protocol) ? "http" : protocol)
            .withHttp2Config(Http2ConfigBuilder.http2Enabled()).withGzipEnabled(false);
        if ("encoded".equals(mode)) {
            builder.withContentEncoders(List.of(new ContentEncoder() {
                @Override public String contentCoding() { return "test"; }
                @Override public boolean prepare(MuRequest request, MuResponse response) { return true; }
                @Override public OutputStream wrapStream(MuRequest request, MuResponse response, OutputStream stream) { return stream; }
            }));
        }
        var server = builder.addHandler((request, response) -> {
            if (request.relativePath().equals("/healthy")) {
                response.write("healthy");
                return true;
            }
            try {
                if ("bodyless".equals(mode)) response.status(204);
                response.write(text);
                var length = response.headers().get("content-length");
                var contentType = response.headers().get("content-type");
                assertThrows(IllegalStateException.class, () -> response.write(text));
                assertThrows(IllegalStateException.class, () -> response.write("must not replace the body"));
                assertThrows(IllegalStateException.class, () -> response.sendChunk("must not append"));
                assertThrows(IllegalStateException.class, response::outputStream);
                assertThrows(IllegalStateException.class, () -> response.outputStream(0));
                assertThrows(IllegalStateException.class, response::writer);
                assertEquals(length, response.headers().get("content-length"));
                assertEquals(contentType, response.headers().get("content-type"));
                assertEquals(ResponseState.FULL_SENT, response.responseState());
                checked.complete(null);
            } catch (Throwable failure) {
                checked.completeExceptionally(failure);
            }
            return true;
        }).start();
        try {
            exerciseConnection(server, protocol, "head".equals(mode) ? Method.HEAD : Method.GET,
                "bodyless".equals(mode) ? 204 : 200, "head".equals(mode) || "bodyless".equals(mode) ? "" : text, checked);
        } finally {
            stopAndCheck(server);
        }
    }

    static Stream<Arguments> startedResponses() {
        return Stream.of("http", "https", "h2", "h2c").flatMap(protocol ->
            Stream.of("buffered", "unbuffered", "writer", "chunk")
                .map(mode -> Arguments.of(protocol, mode)));
    }

    @ParameterizedTest
    @MethodSource("startedResponses")
    void writeRejectsAnAlreadySelectedOutputMethod(String protocol, String mode) throws Exception {
        var checked = new CompletableFuture<Void>();
        var server = httpsServerForTest("h2c".equals(protocol) ? "http" : protocol)
            .withHttp2Config(Http2ConfigBuilder.http2Enabled()).withGzipEnabled(false)
            .addHandler((request, response) -> {
                if (request.relativePath().equals("/healthy")) {
                    response.write("healthy");
                    return true;
                }
                try {
                    response.contentType(ContentTypes.TEXT_PLAIN_UTF8);
                    response.headers().set(HeaderNames.CONTENT_LENGTH, 13);
                    if ("writer".equals(mode)) response.writer().print("original");
                    else if ("chunk".equals(mode)) response.sendChunk("original");
                    else response.outputStream("unbuffered".equals(mode) ? 0 : 8192)
                        .write("original".getBytes(StandardCharsets.UTF_8));
                    var length = response.headers().get("content-length");
                    assertThrows(IllegalStateException.class, () -> response.write("replacement"));
                    assertEquals(length, response.headers().get("content-length"));
                    // Rejection must leave the selected output method usable.
                    if ("writer".equals(mode)) response.writer().print(" tail");
                    else if ("chunk".equals(mode)) response.sendChunk(" tail");
                    else response.outputStream().write(" tail".getBytes(StandardCharsets.UTF_8));
                    checked.complete(null);
                } catch (Throwable failure) {
                    checked.completeExceptionally(failure);
                }
                return true;
            }).start();
        try {
            exerciseConnection(server, protocol, Method.GET, 200, "original tail", checked);
        } finally {
            stopAndCheck(server);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"http", "https", "h2", "h2c"})
    void streamedCompletionRejectsFurtherOutput(String protocol) throws Exception {
        var completed = new CompletableFuture<MuResponse>();
        var server = httpsServerForTest("h2c".equals(protocol) ? "http" : protocol)
            .withHttp2Config(Http2ConfigBuilder.http2Enabled()).withGzipEnabled(false)
            .addHandler((request, response) -> {
                if (request.relativePath().equals("/healthy")) response.write("healthy");
                else {
                    response.addCompletionListener(info -> completed.complete(response));
                    response.contentType(ContentTypes.TEXT_PLAIN_UTF8);
                    response.headers().set(HeaderNames.CONTENT_LENGTH, 8);
                    response.outputStream().write("original".getBytes(StandardCharsets.UTF_8));
                }
                return true;
            }).start();
        try {
            exerciseConnection(server, protocol, Method.GET, 200, "original", completed);
            var response = completed.get(5, TimeUnit.SECONDS);
            assertEquals(ResponseState.FINISHED, response.responseState());
            assertThrows(IllegalStateException.class, () -> response.write("late"));
            assertThrows(IllegalStateException.class, () -> response.sendChunk("late"));
            assertThrows(IllegalStateException.class, response::outputStream);
            assertThrows(IllegalStateException.class, () -> response.outputStream(0));
            assertThrows(IllegalStateException.class, response::writer);
        } finally {
            stopAndCheck(server);
        }
    }

    private static void exerciseConnection(MuServer server, String protocol, Method method, int status, String body,
                                           CompletableFuture<?> checked) throws Exception {
        if (protocol.startsWith("h2")) {
            try (var client = new H2Client();
                 var con = "h2c".equals(protocol) ? client.connectClearText(server) : client.connect(server)) {
                con.socket().setSoTimeout(3000);
                con.handshake();
                for (int i = 0; i < 2; i++) {
                    var headers = getHelloHeaders("h2c".equals(protocol) ? "http" : "https", server.uri().getPort());
                    headers.set(":method", i == 0 ? method.name() : "GET");
                    headers.set(":path", i == 0 ? "/" : "/healthy");
                    con.writeFrame(new Http2HeadersFrame(1 + i * 2, true, headers)).flush();
                    if (i == 0) checked.get(5, TimeUnit.SECONDS);
                    var received = readIgnoringWindowUpdates(con, Http2HeadersFrame.class);
                    assertEquals(Integer.toString(i == 0 ? status : 200), received.headers().get(":status"));
                    var bytes = new ByteArrayOutputStream();
                    boolean done = received.endStream();
                    while (!done) {
                        var data = readIgnoringWindowUpdates(con, Http2DataFrame.class);
                        bytes.write(data.payload(), data.payloadOffset(), data.payloadLength());
                        done = data.endStream();
                    }
                    assertEquals(i == 0 ? body : "healthy", bytes.toString(StandardCharsets.UTF_8));
                }
            }
        } else {
            try (var con = Http1Client.connect(server)) {
                for (int i = 0; i < 2; i++) {
                    con.writeRequestLine(i == 0 ? method : Method.GET, i == 0 ? "/" : "/healthy").flushHeaders();
                    if (i == 0) checked.get(5, TimeUnit.SECONDS);
                    assertEquals(i == 0 && status == 204 ? "HTTP/1.1 204 No Content" : "HTTP/1.1 200 OK", con.readLine());
                    var headers = con.readHeaders();
                    assertEquals(i == 0 ? body : "healthy", i == 0 && method == Method.HEAD ? "" : con.readBody(headers));
                }
            }
        }
    }
}
