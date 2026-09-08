package io.muserver;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import scaffolding.ServerUtils;

import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class IncompleteResponseTest {
    @ParameterizedTest
    @CsvSource({"HTTP/1.0,none", "HTTP/1.1,none", "HTTP/1.1,stream-empty",
        "HTTP/1.1,stream-short", "HTTP/1.1,writer-empty", "HTTP/1.1,writer-short",
        "HTTP/1.1,closed-writer-short", "HTTP/1.1,async-none", "HTTP/1.1,async-short"})
    void incompleteBodyClosesConnectionAndReportsFailure(String version, String style) throws Exception {
        var completed = new CompletableFuture<ResponseInfo>();
        var nextRequests = new AtomicInteger();
        try (var server = ServerUtils.httpsServerForTest("http")
            .addResponseCompleteListener(completed::complete)
            .addHandler(Method.GET, "/", (request, response, params) -> {
                response.headers().set("content-length", "5");
                response.headers().set("connection", "keep-alive");
                if (style.startsWith("async")) {
                    var async = request.handleAsync();
                    if (style.endsWith("short")) {
                        async.write(ByteBuffer.wrap(new byte[] {'a', 'b'}), async::complete);
                    } else {
                        async.complete();
                    }
                } else if (style.contains("stream")) {
                    var out = response.outputStream();
                    if (style.endsWith("short")) out.write(new byte[] {'a', 'b'});
                } else if (style.contains("writer")) {
                    var writer = response.writer();
                    if (style.endsWith("short")) writer.write("ab");
                    if (style.startsWith("closed")) writer.close();
                }
            })
            .addHandler(Method.GET, "/next", (request, response, params) -> {
                nextRequests.incrementAndGet();
                response.write("next");
            }).start();
             var socket = new Socket("localhost", server.uri().getPort())) {
            socket.setSoTimeout(2000);
            String requests = "GET / " + version + "\r\nHost: localhost\r\nConnection: keep-alive\r\n\r\n"
                + "GET /next " + version + "\r\nHost: localhost\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(requests.getBytes(StandardCharsets.US_ASCII));
            String wire = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
            assertTrue(wire.startsWith("HTTP/1.1 200"), wire);
            assertEquals(style.endsWith("short") ? "ab" : "", wire.substring(wire.indexOf("\r\n\r\n") + 4), wire);
            assertEquals(0, nextRequests.get(), "An incomplete response connection was reused");
            var info = completed.get(2, TimeUnit.SECONDS);
            assertFalse(info.completedSuccessfully());
            assertEquals(ResponseState.ERRORED, info.response().responseState());
        }
    }

    @ParameterizedTest
    @CsvSource({"0,none", "5,stream", "5,writer"})
    void completeBodyKeepsConnectionReusable(int length, String style) throws Exception {
        var completed = new CompletableFuture<ResponseInfo>();
        try (var server = ServerUtils.httpsServerForTest("http")
            .addResponseCompleteListener(completed::complete)
            .addHandler(Method.GET, "/", (request, response, params) -> {
                response.headers().set("content-length", length);
                if (style.equals("stream")) response.outputStream().write("hello".getBytes(StandardCharsets.US_ASCII));
                if (style.equals("writer")) response.writer().write("hello");
            })
            .addHandler(Method.GET, "/next", (request, response, params) -> response.write("next"))
            .start();
             var socket = new Socket("localhost", server.uri().getPort())) {
            socket.setSoTimeout(2000);
            socket.getOutputStream().write(("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n"
                + "GET /next HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
            String wire = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
            String afterHeaders = wire.substring(wire.indexOf("\r\n\r\n") + 4);
            assertTrue(afterHeaders.startsWith((length == 0 ? "" : "hello") + "HTTP/1.1 200"), wire);
            assertTrue(wire.endsWith("next"), wire);
            assertTrue(completed.get(2, TimeUnit.SECONDS).completedSuccessfully());
        }
    }
}
