package io.muserver;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class Http1BodyCleanupTest {
    static Stream<Arguments> cases() {
        return Stream.of("valid", "chunk", "trailer", "size", "already-413", "timeout", "eof")
            .flatMap(ending -> Stream.of(false, true).map(started -> Arguments.of(ending, started)));
    }

    @ParameterizedTest @MethodSource("cases")
    void channelCleanupPreservesSocketWireAndCompletionOutcomes(String ending, boolean started) throws Exception {
        List<Object> socket = outcome(false, ending, started);
        List<Object> channel = outcome(true, ending, started);
        assertEquals(socket, channel);
    }

    private List<Object> outcome(boolean channel, String ending, boolean started) throws Exception {
        var handled = new CompletableFuture<Void>();
        var completed = new CompletableFuture<ResponseInfo>();
        var builder = MuServerBuilder.httpServer().withMaxRequestSize(2).withRequestTimeout(100, TimeUnit.MILLISECONDS)
            .addHandler((request, response) -> {
                response.status(ending.equals("already-413") ? 413 : 200);
                response.addCompletionListener(completed::complete);
                if (started) response.sendChunk("prefix");
                handled.complete(null);
                return true;
            });
        builder.useChannelTransport = channel;
        try (MuServer server = builder.start(); var peer = new Socket("localhost", server.uri().getPort())) {
            peer.setSoTimeout(3000);
            peer.getOutputStream().write("POST / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nTransfer-Encoding: chunked\r\n\r\n"
                .getBytes(StandardCharsets.US_ASCII));
            handled.get(3, TimeUnit.SECONDS);
            String body;
            switch (ending) {
                case "valid": body = "2\r\nab\r\n0\r\nx-note: yes\r\n\r\n"; break;
                case "chunk": body = "z\r\n"; break;
                case "trailer": body = "0\r\nContent-Length: 1\r\n\r\n"; break;
                case "size": case "already-413": body = "3\r\nabc\r\n0\r\n\r\n"; break;
                case "timeout": body = ""; break;
                case "eof": body = "2\r\na"; break;
                default: throw new AssertionError(ending);
            }
            peer.getOutputStream().write(body.getBytes(StandardCharsets.US_ASCII));
            if (ending.equals("eof")) peer.shutdownOutput();
            String wire = new String(peer.getInputStream().readAllBytes(), StandardCharsets.US_ASCII)
                .replaceAll("(?im)^date: [^\\r\\n]*\\r\\n", "");
            ResponseInfo result = completed.get(3, TimeUnit.SECONDS);
            String trailer = "unavailable";
            try { trailer = String.valueOf(result.request().trailers().get("x-note")); }
            catch (IllegalStateException incomplete) { /* Unfinished bodies deliberately hide trailers. */ }
            if (ending.equals("valid")) {
                assertTrue(result.completedSuccessfully());
                assertEquals("yes", trailer);
            }
            return List.of(wire, result.completedSuccessfully(), result.response().responseState(), trailer);
        }
    }
}
