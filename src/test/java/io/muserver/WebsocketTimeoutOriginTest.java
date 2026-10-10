package io.muserver;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import scaffolding.Http1Client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static scaffolding.ClientUtils.client;
import static scaffolding.ServerUtils.httpsServerForTest;

@Timeout(20)
class WebsocketTimeoutOriginTest {
    static Stream<Arguments> applicationTimeouts() {
        return Stream.of(false, true).flatMap(tls -> Stream.of(false, true).flatMap(callbackFailure ->
            Stream.of(false, true).map(socketTimeout -> Arguments.of(tls, callbackFailure, socketTimeout))));
    }

    @ParameterizedTest(name = "tls={0}, callbackFailure={1}, socketTimeout={2}")
    @MethodSource("applicationTimeouts")
    void applicationTimeoutStillSendsTheDefaultCloseFrame(boolean tls, boolean callbackFailure, boolean socketTimeout) throws Exception {
        Exception failure = socketTimeout ? new SocketTimeoutException("application timeout")
            : new TimeoutException("application timeout");
        var reported = new CompletableFuture<Throwable>();
        var closing = new CompletableFuture<Integer>();
        var errors = new AtomicInteger();
        var application = new BaseWebSocket() {
            @Override public void onText(String text, boolean last, DoneCallback completion) throws Exception {
                if (callbackFailure) completion.onComplete(failure);
                else throw failure;
            }
            @Override public void onError(Throwable cause) throws Exception {
                errors.incrementAndGet();
                reported.complete(cause);
                super.onError(cause);
            }
        };
        try (var server = httpsServerForTest(tls ? "https" : "http")
            .addHandler(WebSocketHandlerBuilder.webSocketHandler((request, headers) -> application)
                .withPingInterval(0, TimeUnit.SECONDS)).start()) {
            WebSocket peer = client.newWebSocket(new Request.Builder().url(server.uri().toString().replace("http", "ws")).build(),
                new WebSocketListener() {
                    @Override public void onOpen(WebSocket socket, Response response) { socket.send("trigger"); }
                    @Override public void onClosing(WebSocket socket, int code, String reason) {
                        closing.complete(code);
                        socket.close(code, reason);
                    }
                    @Override public void onFailure(WebSocket socket, Throwable cause, Response response) {
                        closing.completeExceptionally(cause);
                    }
                });
            try {
                assertSame(failure, reported.get(5, TimeUnit.SECONDS));
                assertEquals(3008, closing.get(5, TimeUnit.SECONDS));
                assertEquals(1, errors.get());
            } finally { peer.cancel(); }
        }
    }

    @Test
    void readTimeoutWinsOverTheWriteFailureInducedByClosingTheTransport() throws Exception {
        var readTimeout = new SocketTimeoutException("original read timeout");
        var writeFailure = new IOException("write released by transport closure");
        var writeStarted = new CountDownLatch(1);
        var transportClosed = new CountDownLatch(1);
        var writerExited = new CountDownLatch(1);
        var pendingWrite = new CompletableFuture<java.util.concurrent.Future<?>>();
        var reported = new CompletableFuture<Throwable>();
        var errors = new AtomicInteger();
        var live = new CompletableFuture<Http1Connection>();
        var workers = Executors.newFixedThreadPool(2);
        try (var server = MuServerBuilder.httpServer().addHandler((request, response) -> {
            live.complete((Http1Connection) request.connection());
            response.write("ok");
            return true;
        }).start(); var peer = Http1Client.connect(server)) {
            peer.writeRequestLine(Method.GET, "/").endHeaders().flush();
            var connection = live.get(5, TimeUnit.SECONDS);
            var transport = new Socket() {
                @Override public void close() throws IOException {
                    transportClosed.countDown();
                    // Force the competing writer through its error-event CAS before close returns.
                    await(writerExited);
                }
            };
            var parent = new Http1Connection(connection.server, connection.creator, connection.clientSocket, transport,
                connection.clientCertificate, ConnectionAcceptedTime.now(), null, workers);
            var application = new SimpleWebSocket() {
                @Override public void onText(String message) { }
                @Override public void onBinary(ByteBuffer message) { }
                @Override public void onConnect(MuWebSocketSession session) throws Exception {
                    super.onConnect(session);
                    pendingWrite.complete(workers.submit(() -> {
                        try { assertSame(writeFailure, assertThrows(IOException.class,
                            () -> session.sendBinary(ByteBuffer.wrap(new byte[]{1})))); }
                        finally { writerExited.countDown(); }
                    }));
                }
                @Override public void onError(Throwable cause) {
                    errors.incrementAndGet();
                    reported.complete(cause);
                }
            };
            var websocket = new WebsocketConnection(parent, application,
                new WebSocketHandlerBuilder.Settings(0, 65536, 65536, 1000));
            InputStream input = new InputStream() {
                @Override public int read() throws IOException { await(writeStarted); throw readTimeout; }
            };
            OutputStream output = new OutputStream() {
                @Override public void write(int value) throws IOException { await(transportClosed); throw writeFailure; }
                @Override public void write(byte[] data, int offset, int length) throws IOException {
                    writeStarted.countDown();
                    write(data[offset]);
                }
            };
            websocket.runAndBlockUntilDone(input, output, new byte[8192]);
            pendingWrite.get(5, TimeUnit.SECONDS).get(5, TimeUnit.SECONDS);
            assertSame(readTimeout, reported.get(5, TimeUnit.SECONDS));
            assertEquals(1, errors.get());
            assertEquals(WebsocketSessionState.TIMED_OUT, websocket.state());
        } finally {
            transportClosed.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IOException("Test interleaving did not complete");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }
}
