package io.muserver;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import scaffolding.Http1Client;
import scaffolding.MuAssert;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;

import static io.muserver.FieldConformanceFixtures.octets;
import static io.muserver.MuServerBuilder.httpServer;
import static io.muserver.MuServerBuilder.httpsServer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.*;

class Http1ConnectionTerminationTest {

    private @Nullable MuServer server;

    static Stream<Arguments> malformedInitialFields() {
        return Stream.concat(
            Stream.of("Bad(Name: value\r\n", "X-Value: has\0control\r\n", "X-Name : value\r\n",
                "X-Fold: one\r\n two\r\n")
                .flatMap(field -> Stream.of(false, true).map(expect -> Arguments.of(false, field, expect))),
            Stream.of(Arguments.of(true, "Bad(Name: value\r\n", false),
                Arguments.of(true, "X-Value: has\0control\r\n", true)));
    }

    @ParameterizedTest(name = "TLS={0}, field={1}, withheld Expect body={2}")
    @MethodSource("malformedInitialFields")
    void malformedInitialFieldsReceive400AndEofWithoutAnyDispatch(boolean tls, String field, boolean expect) throws Exception {
        CompletableFuture<String> firstDispatched = new CompletableFuture<>();
        CompletableFuture<String> markerDispatched = new CompletableFuture<>();
        server = (tls ? httpsServer() : httpServer())
            .addHandler(Method.POST, "/field-first", (request, response, params) -> {
                firstDispatched.complete(request.uri().getPath());
                response.write("unexpected first");
            })
            .addHandler(Method.GET, "/field-marker", (request, response, params) -> {
                markerDispatched.complete(request.uri().getPath());
                response.write("unexpected marker");
            }).start();
        try (Socket socket = fieldSocket(tls)) {
            String wire = "POST /field-first HTTP/1.1\r\nHost: localhost\r\n" + field
                + (expect ? "Expect: 100-continue\r\nContent-Length: 9\r\n\r\n"
                : "Content-Length: 0\r\n\r\n" + markerRequest());
            // The Expect case sends neither the declared body nor a write-half-close.
            socket.getOutputStream().write(octets(wire));
            socket.getOutputStream().flush();
            String response = readToEof(socket);
            assertAll(
                () -> assertTrue(response.startsWith("HTTP/1.1 400 "), "Expected 400 before EOF: " + response),
                () -> assertFalse(response.contains("HTTP/1.1 100 "), "Do not invite an invalid request's body"),
                () -> assertEquals(1, statusLines(response), "No response for a pipelined successor"),
                () -> assertFalse(firstDispatched.isDone(), "Malformed request reached the application"),
                () -> assertFalse(markerDispatched.isDone(), "Successor reached the application"));
        }
    }

    static Stream<Arguments> validInitialFields() {
        return Stream.concat(Stream.of("Good-Name: value\r\n", "X-Value: has\tvalue\r\n",
            "X-Name: value\r\n", "X-Fold: one two\r\n").map(field -> Arguments.of(false, field)),
            Stream.of(Arguments.of(true, "Good-Name: value\r\n")));
    }

    @ParameterizedTest
    @MethodSource("validInitialFields")
    void matchingValidFieldsDispatchBothPipelinedRequests(boolean tls, String field) throws Exception {
        CompletableFuture<String> firstDispatched = new CompletableFuture<>();
        CompletableFuture<String> markerDispatched = new CompletableFuture<>();
        server = (tls ? httpsServer() : httpServer())
            .addHandler(Method.POST, "/field-first", (request, response, params) -> {
                firstDispatched.complete(request.uri().getPath());
                response.write("first");
            })
            .addHandler(Method.GET, "/field-marker", (request, response, params) -> {
                markerDispatched.complete(request.uri().getPath());
                response.write("marker");
            }).start();
        try (Socket socket = fieldSocket(tls)) {
            socket.getOutputStream().write(octets("POST /field-first HTTP/1.1\r\nHost: localhost\r\n"
                + field + "Content-Length: 0\r\n\r\n" + markerRequest()));
            socket.getOutputStream().flush();
            String response = readToEof(socket);
            assertEquals("/field-first", firstDispatched.get(5, TimeUnit.SECONDS));
            assertEquals("/field-marker", markerDispatched.get(5, TimeUnit.SECONDS));
            assertEquals(2, statusLines(response));
            assertTrue(response.startsWith("HTTP/1.1 200 "));
            assertTrue(response.contains("first"));
            assertTrue(response.contains("marker"));
        }
    }

    static Stream<Arguments> lateTrailerFields() {
        Stream<Arguments> plain = Stream.of("Bad Name: value\r\n", "X-Value: has\0control\r\n",
            "Content-Length: 0\r\n", "X-Fold: one\r\n two\r\n", "Missing-Colon\r\n", "X-Bad :\r\n")
            .flatMap(field -> Stream.of(false, true).map(committed -> Arguments.of(false, committed, false, field)));
        return Stream.concat(plain, Stream.of(
            Arguments.of(true, true, false, "X-Value: has\0control\r\n"),
            Arguments.of(false, false, true, "X-Checksum: MiXeD\r\n"),
            Arguments.of(false, true, true, "X-Checksum: MiXeD\r\n"),
            Arguments.of(true, true, true, "X-Checksum: MiXeD\r\n")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void validExpectRequestCanWaitForContinueThenPipelineItsSuccessor(boolean tls) throws Exception {
        CompletableFuture<String> bodyObserved = new CompletableFuture<>();
        CompletableFuture<String> markerDispatched = new CompletableFuture<>();
        server = (tls ? httpsServer() : httpServer())
            .addHandler(Method.POST, "/field-first", (request, response, params) -> {
                try {
                    bodyObserved.complete(request.readBodyAsString());
                } catch (Exception failure) {
                    bodyObserved.completeExceptionally(failure);
                    throw failure;
                }
                response.write("first");
            })
            .addHandler(Method.GET, "/field-marker", (request, response, params) -> {
                markerDispatched.complete(request.uri().getPath());
                response.write("marker");
            }).start();
        try (Socket socket = fieldSocket(tls)) {
            socket.getOutputStream().write(octets("POST /field-first HTTP/1.1\r\nHost: localhost\r\n"
                + "X-Good: MiXeD\r\nExpect: 100-continue\r\nContent-Length: 9\r\n\r\n"));
            socket.getOutputStream().flush();
            ByteArrayOutputStream interim = new ByteArrayOutputStream();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!interim.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) {
                assertTrue(System.nanoTime() < deadline, "No interim response by deadline");
                int b = socket.getInputStream().read();
                assertNotEquals(-1, b, "EOF before the interim response");
                interim.write(b);
                assertTrue(interim.size() <= 8192);
            }
            assertTrue(interim.toString(StandardCharsets.US_ASCII).startsWith("HTTP/1.1 100 "));
            socket.getOutputStream().write(octets("body-data" + markerRequest()));
            socket.getOutputStream().flush();
            String response = readToEof(socket);
            assertEquals("body-data", bodyObserved.get(5, TimeUnit.SECONDS));
            assertEquals("/field-marker", markerDispatched.get(5, TimeUnit.SECONDS));
            assertEquals(2, statusLines(response));
            assertTrue(response.startsWith("HTTP/1.1 200 "));
        }
    }

    private static class TrailerObservation {
        final String body;
        final Exception failure;
        final boolean trailersUnavailable;
        final String checksum;

        TrailerObservation(String body, Exception failure, boolean trailersUnavailable, String checksum) {
            this.body = body;
            this.failure = failure;
            this.trailersUnavailable = trailersUnavailable;
            this.checksum = checksum;
        }
    }

    @ParameterizedTest(name = "TLS={0}, response started={1}, valid={2}, trailer={3}")
    @MethodSource("lateTrailerFields")
    void lateTrailerErrorsFailTheReaderAndCloseWithoutASecondResponse(boolean tls, boolean committed,
                                                                       boolean valid, String field) throws Exception {
        CompletableFuture<Void> ready = new CompletableFuture<>();
        CompletableFuture<TrailerObservation> observed = new CompletableFuture<>();
        CompletableFuture<String> markerDispatched = new CompletableFuture<>();
        CompletableFuture<ResponseInfo> completed = new CompletableFuture<>();
        server = (tls ? httpsServer() : httpServer())
            .addResponseCompleteListener(info -> {
                if ("/field-late".equals(info.request().uri().getPath())) completed.complete(info);
            })
            .addHandler(Method.POST, "/field-late", (request, response, params) -> {
                if (committed) {
                    response.headers().set(HeaderNames.CONTENT_LENGTH, 100);
                    var out = response.outputStream(0);
                    out.write(octets("started"));
                    out.flush();
                }
                ready.complete(null);
                String body;
                try {
                    body = request.readBodyAsString();
                } catch (Exception failure) {
                    boolean unavailable = false;
                    try {
                        request.trailers();
                    } catch (IllegalStateException notComplete) {
                        unavailable = true;
                    }
                    observed.complete(new TrailerObservation(null, failure, unavailable, null));
                    throw failure;
                }
                observed.complete(new TrailerObservation(body, null, false, request.trailers().get("x-checksum")));
                if (committed) response.outputStream().write(octets("x".repeat(93)));
                else response.write("complete");
            })
            .addHandler(Method.GET, "/field-marker", (request, response, params) -> {
                markerDispatched.complete(request.uri().getPath());
                response.write("marker");
            }).start();

        try (Socket socket = fieldSocket(tls)) {
            socket.getOutputStream().write(octets("POST /field-late HTTP/1.1\r\nHost: localhost\r\n"
                + "Transfer-Encoding: chunked\r\n\r\n1\r\nx\r\n"));
            socket.getOutputStream().flush();
            ready.get(5, TimeUnit.SECONDS);
            // The malformed trailer cannot arrive until response output has actually been flushed.
            socket.getOutputStream().write(octets("0\r\n" + field + "\r\n" + markerRequest()));
            socket.getOutputStream().flush();
            String response = readToEof(socket);
            TrailerObservation result = observed.get(5, TimeUnit.SECONDS);
            ResponseInfo completion = completed.get(5, TimeUnit.SECONDS);
            if (valid) {
                assertNull(result.failure);
                assertEquals("x", result.body);
                assertEquals("MiXeD", result.checksum);
                assertEquals("/field-marker", markerDispatched.get(5, TimeUnit.SECONDS));
                assertEquals(2, statusLines(response));
                assertTrue(completion.completedSuccessfully());
            } else {
                assertAll(
                    () -> assertNotNull(result.failure, "Malformed trailer was reported as successful body EOF"),
                    () -> assertTrue(result.trailersUnavailable, "Rejected trailers must not become available"),
                    () -> assertFalse(markerDispatched.isDone(), "Successor must not dispatch"),
                    () -> assertFalse(completion.completedSuccessfully(), "Body failure must not be reported as success"),
                    () -> assertTrue(statusLines(response) <= 1, "Must not emit a second status line"));
                if (committed) {
                    assertTrue(response.startsWith("HTTP/1.1 200 "));
                    assertTrue(response.contains("started"), "The first response must have begun");
                    assertEquals(1, statusLines(response));
                    int bodyStart = response.indexOf("\r\n\r\n") + 4;
                    assertTrue(response.length() - bodyStart < 100, "Failure must not fabricate successful response completion");
                }
            }
        }
    }

    private Socket fieldSocket(boolean tls) throws Exception {
        Socket socket;
        if (tls) {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{scaffolding.ClientUtils.veryTrustingTrustManager()}, null);
            socket = context.getSocketFactory().createSocket();
        } else {
            socket = new Socket();
        }
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", server.uri().getPort()), 5000);
            socket.setSoTimeout(5000);
            if (socket instanceof SSLSocket) ((SSLSocket) socket).startHandshake();
            return socket;
        } catch (Exception failure) {
            socket.close();
            throw failure;
        }
    }

    private static String markerRequest() {
        return "GET /field-marker HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
    }

    private static int statusLines(String response) {
        return response.split("HTTP/1\\.1 ", -1).length - 1;
    }

    private static String readToEof(Socket socket) throws IOException {
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        byte[] bytes = new byte[4096];
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            long remaining = deadline - System.nanoTime();
            assertTrue(remaining > 0, "Connection did not close by the deadline");
            socket.setSoTimeout((int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
            int count = socket.getInputStream().read(bytes);
            if (count == -1) return received.toString(StandardCharsets.ISO_8859_1);
            received.write(bytes, 0, count);
            assertTrue(received.size() <= 65536, "Response exceeded the byte limit");
        }
    }

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
    void oversizedChunkSizeDoesNotDispatchFollowingRequest() throws Exception {
        var markerRequests = new AtomicInteger();
        server = httpServer()
            .withMaxRequestSize(1)
            .addHandler(Method.POST, "/", (request, response, pathParams) -> {
                request.readBodyAsString();
                response.write("unexpected");
            })
            .addHandler(Method.GET, "/smuggled", (request, response, pathParams) -> {
                markerRequests.incrementAndGet();
                response.write("marker");
            })
            .start();

        try (var socket = new Socket("127.0.0.1", server.uri().getPort())) {
            socket.setSoTimeout(3000);
            var wire = "POST / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nTransfer-Encoding: chunked\r\n\r\n"
                + "100000004\r\n"
                + "GET /smuggled HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                + "x".repeat(16384);
            socket.getOutputStream().write(wire.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();

            var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            assertThat(input.readLine(), startsWith("HTTP/1.1 413 "));
            assertEquals(0, markerRequests.get());
        }
    }

    @Test
    void whitespaceAfterChunkSizeDoesNotDispatchFollowingRequest() throws Exception {
        var markerRequests = new AtomicInteger();
        server = httpServer()
            .addHandler(Method.POST, "/", (request, response, pathParams) -> {
                request.readBodyAsString();
                response.write("unexpected");
            })
            .addHandler(Method.GET, "/smuggled", (request, response, pathParams) -> {
                markerRequests.incrementAndGet();
                response.write("marker");
            })
            .start();

        try (var socket = new Socket("127.0.0.1", server.uri().getPort())) {
            socket.setSoTimeout(3000);
            var wire = "POST / HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n"
                + "5 c\r\n"
                + "GET /smuggled HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(wire.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();

            byte[] response = new byte[4096];
            var responseBytes = new ByteArrayOutputStream();
            int received = 0;
            int read;
            while ((read = socket.getInputStream().read(response)) != -1) {
                received += read;
                assertTrue(received <= 65536, "Unexpectedly large rejection response");
                responseBytes.write(response, 0, read);
            }
            assertThat(responseBytes.toString(StandardCharsets.US_ASCII), startsWith("HTTP/1.1 500 "));
            assertEquals(0, markerRequests.get());
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
