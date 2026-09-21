package io.muserver;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static io.muserver.RFCTestUtils.getHelloHeaders;
import static org.junit.jupiter.api.Assertions.*;
import static scaffolding.ServerUtils.httpsServerForTest;

@Timeout(20)
class Http2FinalWriteCompletionTest {
    static Stream<Arguments> finalWrites() {
        return Stream.of(false, true).flatMap(dataFrame -> Stream.of(false, true).flatMap(duringFlush ->
            Stream.of(false, true).map(fail -> Arguments.of(dataFrame, duringFlush, fail))));
    }

    @ParameterizedTest
    @MethodSource("finalWrites")
    void peerCloseDuringTheFinalWriteWaitsForItsOutcome(boolean dataFrame, boolean duringFlush, boolean fail) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try (var server = httpsServerForTest("h2").start();
             var client = new H2Client(); var con = client.connect(server)) {
            con.handshake();
            var live = (Http2Connection) server.activeConnections().iterator().next();
            // Exercise the real writer with controlled output, independently of socket buffering.
            var writer = new Http2Connection(live.server, live.creator, live.clientSocket,
                live.clientCertificate, ConnectionAcceptedTime.now(), live.proxyInfo().orElse(null), Http2Settings.DEFAULT_CLIENT_SETTINGS,
                5000, executor, executor);
            try {
                var stream = Http2Stream.start(writer,
                    new Http2HeadersFrame(1, true, getHelloHeaders(server.uri().getPort())));
                stream.response().status(dataFrame ? 200 : 304);
                stream.response().setState(ResponseState.WRITING_HEADERS);
                var async = (Mu3AsyncHandleImpl) stream.request.handleAsync();
                writer.testProbe().streams().registerApplicationStream(stream);
                writer.testProbe().coordinator().openStream(1, 65_535, Http2StreamState.HALF_CLOSED_REMOTE, stream);
                var headers = new FieldBlock();
                headers.set(":status", dataFrame ? "200" : "304");
                if (dataFrame) writer.write(new Http2HeadersFrame(1, false, headers));
                var finalWrite = new WriteTask(dataFrame ? Http2DataFrame.eos(1)
                    : new Http2HeadersFrame(1, true, headers), true);
                writer.write(finalWrite);
                var wire = new ByteArrayOutputStream();
                var output = new OutputStream() {
                    private boolean terminalFrame;
                    @Override public void write(int value) { fail("Expected framed output"); }
                    @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                        wire.write(bytes, offset, length);
                        if (length >= 9) terminalFrame = (bytes[offset + 4] & 1) != 0;
                        if (terminalFrame && !duringFlush) pauseFinalWrite();
                    }
                    @Override public void flush() throws IOException {
                        if (terminalFrame && duringFlush) pauseFinalWrite();
                    }
                    private void pauseFinalWrite() throws IOException {
                        entered.countDown();
                        try {
                            if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Test release timed out");
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IOException(e);
                        }
                        if (fail) throw new IOException("Final write failed");
                    }
                };
                writer.startWriteLoop(output);
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertTrue(wire.size() >= 9, "END_STREAM bytes reached output before closure");
                assertEquals(!duringFlush, stream.countsTowardsMaxConcurrentStreams(),
                    "Connection completion must not move the stream-admission publication boundary");
                assertFalse(writer.testProbe().streams().hasActiveConnectionWork());
                stream.onPeerInputClosed(new IOException("Peer closed after reading END_STREAM"));
                assertFalse(stream.resetWasInitiated());
                assertFalse(async.exchangeCompletion().isDone());
                assertFalse(stream.completedSuccessfully());
                release.countDown();
                if (fail) {
                    var error = assertThrows(IOException.class, () -> finalWrite.await(5, TimeUnit.SECONDS));
                    assertEquals("Final write failed", error.getMessage());
                } else {
                    finalWrite.await(5, TimeUnit.SECONDS);
                }
                assertFalse(stream.completedSuccessfully(), "Transport completion must still await the application");
                stream.cleanup();
                stream.onApplicationExchangeEnded();
                assertEquals(!fail, stream.completedSuccessfully());
            } finally {
                release.countDown();
                writer.forceShutdown();
            }
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
