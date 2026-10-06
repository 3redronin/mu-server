package io.muserver;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import scaffolding.Http1Client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.muserver.WebSocketHandlerBuilder.webSocketHandler;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.*;
import static scaffolding.MuAssert.assertEventually;
import static scaffolding.ServerUtils.httpsServerForTest;

@Timeout(20)
class WebsocketAbortTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void repeatedAbortClosesWithoutAFrameAndRejectsSubsequentSends(boolean tls) throws Exception {
        var handler = new RecordingSocket();
        try (var server = start(tls, handler); var peer = new WebSocketWireTestSupport(server)) {
            MuWebSocketSession session = handler.connected.get(5, TimeUnit.SECONDS);
            session.abort();
            session.abort();
            assertEquals(WebsocketSessionState.DISCONNECTED, session.state());
            assertEquals(-1, peer.input.read(), "Abort must not send a WebSocket close frame");
            assertFalse(session.closeSent());
            assertFalse(session.closeReceived());
            assertThrows(IOException.class, () -> session.sendText("late"));
            assertThrows(IOException.class, () -> session.sendTextFragment(ByteBuffer.wrap(new byte[]{1}), false));
            assertThrows(IOException.class, () -> session.sendBinary(ByteBuffer.wrap(new byte[]{1})));
            assertThrows(IOException.class, () -> session.sendBinaryFragment(ByteBuffer.wrap(new byte[]{1}), false));
            assertThrows(IOException.class, () -> session.sendPing(ByteBuffer.wrap(new byte[]{1})));
            assertThrows(IOException.class, () -> session.sendPong(ByteBuffer.wrap(new byte[]{1})));
            assertThrows(IOException.class, session::close);
            assertThrows(IOException.class, () -> session.close(1000, "late"));
            var completion = new CompletableFuture<Throwable>();
            session.sendText("late", completion::complete);
            assertInstanceOf(IOException.class, completion.get(5, TimeUnit.SECONDS));
            assertRetiredWithoutErrors(server, handler);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void abortReleasesAReaderWaitingForAnAsynchronousReceiveCompletion(boolean tls) throws Exception {
        var pendingReceive = new CompletableFuture<DoneCallback>();
        var handler = new RecordingSocket() {
            @Override public void onText(String message, boolean last, DoneCallback completion) {
                pendingReceive.complete(completion);
            }
        };
        try (var server = start(tls, handler); var peer = new WebSocketWireTestSupport(server)) {
            MuWebSocketSession session = handler.connected.get(5, TimeUnit.SECONDS);
            peer.send(true, 1, new byte[]{'x'});
            DoneCallback completion = pendingReceive.get(5, TimeUnit.SECONDS);
            try {
                session.abort();
                assertEquals(-1, peer.input.read());
                assertRetiredWithoutErrors(server, handler);
            } finally {
                // A late completion remains harmless and cannot overwrite the terminal state.
                completion.onComplete(new IOException("late application completion"));
            }
            assertEquals(WebsocketSessionState.DISCONNECTED, session.state());
            assertEquals(0, handler.errors.get());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void abortCanBeCalledInsideOnConnect(boolean tls) throws Exception {
        var returned = new CompletableFuture<Void>();
        var handler = new RecordingSocket() {
            @Override public void onConnect(MuWebSocketSession session) throws Exception {
                super.onConnect(session);
                session.abort();
                returned.complete(null);
            }
        };
        try (var server = start(tls, handler); var peer = new WebSocketWireTestSupport(server)) {
            returned.get(5, TimeUnit.SECONDS);
            assertEquals(-1, peer.input.read());
            assertRetiredWithoutErrors(server, handler);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void abortCanBeCalledInsideAReceiveCallbackWithoutCompletingThatReceive(boolean tls) throws Exception {
        var returned = new CompletableFuture<Void>();
        var handler = new RecordingSocket() {
            @Override public void onText(String message, boolean last, DoneCallback completion) {
                session().abort();
                session().abort();
                returned.complete(null);
            }
        };
        try (var server = start(tls, handler); var peer = new WebSocketWireTestSupport(server)) {
            handler.connected.get(5, TimeUnit.SECONDS);
            peer.send(true, 1, new byte[]{'x'});
            returned.get(5, TimeUnit.SECONDS);
            assertEquals(-1, peer.input.read());
            assertRetiredWithoutErrors(server, handler);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void abortInterruptsALargeAsynchronousWriteToAPeerThatStopsReading(boolean tls) throws Exception {
        var completion = new CompletableFuture<Throwable>();
        var callbacks = new AtomicInteger();
        var handler = new RecordingSocket();
        try (var server = httpsServerForTest(tls ? "https" : "http")
            .addHandler((request, response) -> {
                ((Http1Connection) request.connection()).transportSocket.setSendBufferSize(8192);
                return false;
            }).addHandler(webSocketHandler((request, headers) -> handler)
                .withPingInterval(0, TimeUnit.SECONDS)).start();
             var peer = new WebSocketWireTestSupport(server)) {
            peer.socket.setReceiveBufferSize(8192);
            MuWebSocketSession session = handler.connected.get(5, TimeUnit.SECONDS);
            session.sendBinary(ByteBuffer.allocate(8 * 1024 * 1024), failure -> {
                callbacks.incrementAndGet();
                completion.complete(failure);
            });
            // Observe the frame header, then stop reading its payload.
            assertEquals(0x82, peer.input.readUnsignedByte());
            assertEquals(127, peer.input.readUnsignedByte());
            assertEquals(8 * 1024 * 1024, peer.input.readLong());
            assertFalse(completion.isDone(), "The oversized write must still be pending");
            session.abort();
            assertInstanceOf(IOException.class, completion.get(5, TimeUnit.SECONDS));
            assertEquals(1, callbacks.get());
            assertRetiredWithoutErrors(server, handler);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void abortCanBeCalledFromErrorHandling(boolean tls) throws Exception {
        var failure = new IOException("application failure");
        var reported = new CompletableFuture<Throwable>();
        var handler = new RecordingSocket() {
            @Override public void onText(String message, boolean last, DoneCallback completion) throws Exception {
                throw failure;
            }
            @Override public void onError(Throwable cause) {
                errors.incrementAndGet();
                session().abort();
                reported.complete(cause);
            }
        };
        try (var server = start(tls, handler); var peer = new WebSocketWireTestSupport(server)) {
            handler.connected.get(5, TimeUnit.SECONDS);
            peer.send(true, 1, new byte[]{'x'});
            assertSame(failure, reported.get(5, TimeUnit.SECONDS));
            assertEquals(-1, peer.input.read());
            assertEventually(() -> server.activeConnections().size(), equalTo(0));
            assertEquals(WebsocketSessionState.DISCONNECTED, handler.state());
            assertEquals(1, handler.errors.get());
            assertEquals(0, handler.clientCloses.get());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void abortPreservesACompletedGracefulClose(boolean tls) throws Exception {
        var handler = new RecordingSocket();
        try (var server = start(tls, handler); var peer = new WebSocketWireTestSupport(server)) {
            MuWebSocketSession session = handler.connected.get(5, TimeUnit.SECONDS);
            byte[] normalClose = {3, (byte) 232};
            peer.send(true, 8, normalClose);
            assertArrayEquals(normalClose, peer.readFrame(8));
            assertEquals(-1, peer.input.read());
            assertEventually(session::state, equalTo(WebsocketSessionState.CLIENT_CLOSED));
            session.abort();
            session.abort();
            assertEquals(WebsocketSessionState.CLIENT_CLOSED, session.state());
            assertTrue(session.closeSent());
            assertTrue(session.closeReceived());
            assertEquals(1, handler.clientCloses.get());
            assertEventually(() -> server.activeConnections().size(), equalTo(0));
            assertEquals(0, handler.errors.get());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void abortDoesNotTakeTheWriteLockOrReplaceAnAlreadyQueuedError(boolean errorFirst) throws Exception {
        var writing = new CountDownLatch(1);
        var transportClosed = new CountDownLatch(1);
        var releaseWriter = new CountDownLatch(1);
        var reported = new CompletableFuture<Throwable>();
        var original = new IOException("original write failure");
        var workers = Executors.newFixedThreadPool(3);
        var application = Executors.newSingleThreadExecutor();
        var applicationBlocked = new CountDownLatch(1);
        var releaseApplication = new CountDownLatch(1);
        var live = new CompletableFuture<Http1Connection>();
        try (var server = MuServerBuilder.httpServer().withHandlerExecutor(application).addHandler((request, response) -> {
            live.complete((Http1Connection) request.connection());
            response.write("ok");
            return true;
        }).start(); var peer = Http1Client.connect(server)) {
            peer.writeRequestLine(Method.GET, "/").endHeaders().flush();
            var connection = live.get(5, TimeUnit.SECONDS);
            var transport = new Socket() {
                @Override public void close() { transportClosed.countDown(); }
            };
            var parent = new Http1Connection(connection.server, connection.creator, connection.clientSocket, transport,
                connection.clientCertificate, ConnectionAcceptedTime.now(), null, workers);
            var handler = new RecordingSocket() {
                @Override public void onError(Throwable cause) throws Exception {
                    this.errors.incrementAndGet();
                    session().abort();
                    reported.complete(cause);
                }
            };
            var websocket = new WebsocketConnection(parent, handler,
                new WebSocketHandlerBuilder.Settings(0, 65536, 65536, 1000));
            InputStream input = new InputStream() {
                @Override public int read() throws IOException { await(transportClosed); throw new IOException("aborted read"); }
            };
            OutputStream output = new OutputStream() {
                @Override public void write(int value) throws IOException {
                    writing.countDown();
                    await(releaseWriter);
                    throw original;
                }
            };
            var reader = workers.submit(() -> {
                websocket.runAndBlockUntilDone(input, output, new byte[8192]);
                return null;
            });
            MuWebSocketSession session = handler.connected.get(5, TimeUnit.SECONDS);
            var writer = workers.submit(() -> assertSame(original,
                assertThrows(IOException.class, () -> session.sendText("blocked"))));
            assertTrue(writing.await(5, TimeUnit.SECONDS));
            if (errorFirst) {
                application.submit(() -> {
                    applicationBlocked.countDown();
                    await(releaseApplication);
                    return null;
                });
                assertTrue(applicationBlocked.await(5, TimeUnit.SECONDS));
                releaseWriter.countDown();
                writer.get(5, TimeUnit.SECONDS); // The original error is queued behind the held worker.
                assertFalse(reported.isDone());
                session.abort();
                assertFalse(reported.isDone());
                releaseApplication.countDown();
                assertSame(original, reported.get(5, TimeUnit.SECONDS));
            } else {
                workers.submit(session::abort).get(5, TimeUnit.SECONDS);
                assertFalse(writer.isDone(), "Abort must return while the writer still owns its lock");
                assertThrows(IOException.class, () -> session.sendText("late"));
                releaseWriter.countDown();
            }
            writer.get(5, TimeUnit.SECONDS);
            reader.get(5, TimeUnit.SECONDS);
            assertEquals(errorFirst ? 1 : 0, handler.errors.get());
            assertEquals(WebsocketSessionState.DISCONNECTED, session.state());
        } finally {
            releaseWriter.countDown();
            releaseApplication.countDown();
            transportClosed.countDown();
            workers.shutdownNow();
            application.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(application.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static MuServer start(boolean tls, RecordingSocket handler) throws IOException {
        return httpsServerForTest(tls ? "https" : "http")
            .addHandler(webSocketHandler((request, headers) -> handler).withPingInterval(0, TimeUnit.SECONDS)).start();
    }

    private static void assertRetiredWithoutErrors(MuServer server, RecordingSocket handler) {
        assertEventually(() -> server.activeConnections().size(), equalTo(0));
        assertEquals(WebsocketSessionState.DISCONNECTED, handler.state());
        assertEquals(0, handler.errors.get());
        assertEquals(0, handler.clientCloses.get());
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IOException("Test interleaving did not complete");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    private static class RecordingSocket extends BaseWebSocket {
        final CompletableFuture<MuWebSocketSession> connected = new CompletableFuture<>();
        final AtomicInteger errors = new AtomicInteger();
        final AtomicInteger clientCloses = new AtomicInteger();
        @Override public void onConnect(MuWebSocketSession session) throws Exception {
            super.onConnect(session);
            connected.complete(session);
        }
        @Override public void onError(Throwable cause) throws Exception {
            errors.incrementAndGet();
            super.onError(cause);
        }
        @Override public void onClientClosed(int code, String reason) throws Exception {
            clientCloses.incrementAndGet();
            super.onClientClosed(code, reason);
        }
    }
}
