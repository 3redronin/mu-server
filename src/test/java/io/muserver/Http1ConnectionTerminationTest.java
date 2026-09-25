package io.muserver;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import scaffolding.Http1Client;
import scaffolding.MuAssert;

import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.muserver.MuServerBuilder.httpServer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Http1ConnectionTerminationTest {

    private @Nullable MuServer server;

    @Test
    void invalidContentLengthReceivesBadRequestAndConnectionClose() throws Exception {
        server = httpServer().start();
        try (var client = Http1Client.connect(server)) {
            client.writeRequestLine(Method.POST, "/")
                .writeHeader("Content-Length", "nope")
                .endHeaders()
                .flush();
            assertThat(client.readLine(), startsWith("HTTP/1.1 400 "));
            assertThat(client.readHeaders().get("Connection"), equalTo("close"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void chunkExtensionLineEndingControlsFollowingRequestDispatch(boolean validCrLf) throws Exception {
        var markerRequests = new AtomicInteger();
        server = httpServer()
            .addHandler(Method.POST, "/", (request, response, pathParams) -> {
                request.readBodyAsString();
                response.write("ok");
            })
            .addHandler(Method.GET, "/lf-extension-marker", (request, response, pathParams) -> {
                markerRequests.incrementAndGet();
                response.write("unexpected");
            })
            .start();

        try (var socket = new Socket("127.0.0.1", server.uri().getPort())) {
            socket.setSoTimeout(3000);
            var wire = "POST / HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n"
                + "3D;!" + (validCrLf ? "\r\n" : "\n") + "A".repeat(61) + "\r\n0\r\n\r\n"
                + "GET /lf-extension-marker HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(wire.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();

            byte[] response = new byte[4096];
            int received = 0;
            int read;
            while ((read = socket.getInputStream().read(response)) != -1) {
                received += read;
                assertTrue(received <= 65536, "Unexpectedly large rejection response");
            }
            assertEquals(validCrLf ? 1 : 0, markerRequests.get());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void chunkSizeLineControlsFollowingRequestDispatch(boolean validCrLf) throws Exception {
        var markerRequests = new AtomicInteger();
        server = httpServer()
            .addHandler(Method.POST, "/", (request, response, pathParams) -> {
                request.readBodyAsString();
                response.write("ok");
            })
            .addHandler(Method.GET, "/smuggled", (request, response, pathParams) -> {
                markerRequests.incrementAndGet();
                response.write("marker");
            })
            .start();

        try (var socket = new Socket("127.0.0.1", server.uri().getPort())) {
            socket.setSoTimeout(3000);
            var wire = "POST / HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n"
                + (validCrLf ? "0\r\n\r\n" : "0\r X\r\n\r\n")
                + "GET /smuggled HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(wire.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();

            byte[] response = new byte[4096];
            int received = 0;
            int read;
            while ((read = socket.getInputStream().read(response)) != -1) {
                received += read;
                assertTrue(received <= 65536, "Unexpectedly large rejection response");
            }
            assertEquals(validCrLf ? 1 : 0, markerRequests.get());
        }
    }

    @Test
    void abortedUploadPublishesClientDisconnectBeforeTheReadFails()
        throws Exception {
        var reading = new CountDownLatch(1);
        var stateAtFailure = new CompletableFuture<ResponseState>();
        var completed = new CompletableFuture<ResponseInfo>();
        server = httpServer()
            .addResponseCompleteListener(completed::complete)
            .addHandler(Method.POST, "/", (request, response, pathParams) -> {
                reading.countDown();
                try {
                    request.readBodyAsString();
                } catch (IOException failure) {
                    stateAtFailure.complete(response.responseState());
                    throw failure;
                }
            })
            .start();

        try (var client = Http1Client.connect(server)) {
            client.writeRequestLine(Method.POST, "/")
                .writeHeader(HeaderNames.CONTENT_LENGTH.toString(), 10)
                .endHeaders()
                .writeAscii("x")
                .flush();
            assertThat(reading.await(5, TimeUnit.SECONDS), equalTo(true));

            client.abort();

            assertThat(
                stateAtFailure.get(5, TimeUnit.SECONDS),
                equalTo(ResponseState.CLIENT_DISCONNECTED)
            );
            assertThat(
                completed.get(5, TimeUnit.SECONDS).response().responseState(),
                equalTo(ResponseState.CLIENT_DISCONNECTED)
            );
        }
    }

    @Test
    void abortedResponseWriteCompletesAsClientDisconnected() throws Exception {
        var handlerStarted = new CountDownLatch(1);
        var writeResponse = new CountDownLatch(1);
        var completed = new CompletableFuture<ResponseInfo>();
        server = httpServer()
            .addResponseCompleteListener(completed::complete)
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                handlerStarted.countDown();
                assertThat(
                    writeResponse.await(5, TimeUnit.SECONDS),
                    equalTo(true)
                );
                response.write("too late");
            })
            .start();

        try (var client = Http1Client.connect(server)) {
            client.writeRequestLine(Method.GET, "/").endHeaders().flush();
            assertThat(
                handlerStarted.await(5, TimeUnit.SECONDS),
                equalTo(true)
            );

            client.abort();
            writeResponse.countDown();

            assertThat(
                completed.get(5, TimeUnit.SECONDS).response().responseState(),
                equalTo(ResponseState.CLIENT_DISCONNECTED)
            );
        } finally {
            writeResponse.countDown();
        }
    }

    @AfterEach
    void stopServer() {
        MuAssert.stopAndCheck(server);
    }
}
