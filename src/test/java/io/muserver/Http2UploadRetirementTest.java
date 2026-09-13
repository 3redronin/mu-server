package io.muserver;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import static io.muserver.RFCTestUtils.getHelloHeaders;
import static io.muserver.RFCTestUtils.readIgnoringWindowUpdates;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.*;
import static scaffolding.MuAssert.assertEventually;
import static scaffolding.ServerUtils.httpsServerForTest;

@Timeout(15)
class Http2UploadRetirementTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void writerFailureCompletesAnUnfinishedUploadBeforeRetirement(boolean readerProcessingFrame) throws Exception {
        var accepted = new CompletableFuture<Http2Connection>();
        var completed = new CompletableFuture<ResponseInfo>();
        var notifications = new AtomicInteger();
        var application = Executors.newSingleThreadExecutor();
        var server = httpsServerForTest("http")
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .withMaxConcurrentRequests(1)
            .withHandlerExecutor(application)
            .addResponseCompleteListener(info -> {
                notifications.incrementAndGet();
                completed.complete(info);
            })
            .addHandler((request, response) -> {
                accepted.complete((Http2Connection) request.connection());
                response.status(204);
                return true;
            }).start();
        Http2Stream stream = null;
        try (var client = new H2Client(); var con = client.connectClearText(server)) {
            var headers = getHelloHeaders("http", server.uri().getPort());
            headers.set(":method", "POST");
            con.handshake().writeFrame(new Http2HeadersFrame(1, false, headers)).flush();
            var response = readIgnoringWindowUpdates(con, Http2HeadersFrame.class);
            assertEquals("204", response.headers().get(":status"));
            assertTrue(response.endStream());
            var connection = accepted.get(5, TimeUnit.SECONDS);
            stream = connection.testProbe().streams().applicationStream(1);
            assertNotNull(stream);
            assertEventually(stream::applicationExchangeEnded, is(true));
            application.submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertFalse(stream.requestEnded().isDone());
            assertFalse(completed.isDone());
            assertEquals(1, server.stats().activeRequests().size());

            // Keep peer input open while making the next server write fail. The writer
            // must reach its shutdown path before it closes the socket and wakes the reader.
            if (readerProcessingFrame) {
                failWriterWhileReaderProcessesAPing(connection, con);
            } else {
                connection.clientSocket.shutdownOutput();
                connection.write(new Http2Ping(false, new byte[8]));
            }

            assertTrue(stream.requestEnded().handle((ignored, failure) -> true).get(5, TimeUnit.SECONDS),
                "The disconnected upload must end even if the writer shuts down first");
            assertFalse(completed.get(5, TimeUnit.SECONDS).completedSuccessfully());
            assertEquals(1, notifications.get());
            assertEquals(1, server.stats().completedRequests());
            assertTrue(server.stats().activeRequests().isEmpty());
            assertEventually(() -> connection.testProbe().streams().isEmpty(), is(true));

            // Prove that the completed exchange released the server-wide admission slot.
            try (var next = client.connectClearText(server)) {
                next.handshake().writeFrame(new Http2HeadersFrame(1, true,
                    getHelloHeaders("http", server.uri().getPort()))).flush();
                assertEquals("204", readIgnoringWindowUpdates(next, Http2HeadersFrame.class).headers().get(":status"));
            }
        } finally {
            // Release the intentionally unfinished upload even when testing the broken commit.
            if (stream != null && !stream.requestEnded().isDone()) {
                stream.onPeerInputClosed(new IOException("Test cleanup"));
            }
            server.stop();
            application.shutdownNow();
        }
    }

    private static void failWriterWhileReaderProcessesAPing(Http2Connection connection, H2ClientConnection client) throws Exception {
        var writing = new CountDownLatch(1);
        var failWrite = new CountDownLatch(1);
        var writerThread = new AtomicReference<Thread>();
        var frame = new LogicalHttp2Frame() {
            @Override public void writeTo(Http2Peer peer, OutputStream out) throws IOException {
                writerThread.set(Thread.currentThread());
                writing.countDown();
                try {
                    if (!failWrite.await(5, TimeUnit.SECONDS)) throw new IOException("Test release timed out");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
                throw new IOException("Controlled writer failure");
            }
        };
        var write = new WriteTask(frame, true);
        connection.write(write);
        try {
            assertTrue(writing.await(5, TimeUnit.SECONDS));
            var lifecycle = (ReentrantLock) connection.testProbe().lifecycleLock();
            lifecycle.lock();
            try {
                failWrite.countDown();
                assertEquals("Controlled writer failure",
                    assertThrows(IOException.class, () -> write.await(5, TimeUnit.SECONDS)).getMessage());
                assertEventually(() -> lifecycle.hasQueuedThread(writerThread.get()), is(true));
                // Queue the reader behind the failing writer at the lifecycle lock.
                // Once released, the reader finishes this PING and exits its loop
                // without another socket read that could observe the writer's close.
                client.writeFrame(new Http2Ping(false, new byte[8])).flush();
                assertEventually(() -> lifecycle.getQueueLength(), is(2));
            } finally {
                lifecycle.unlock();
            }
        } finally {
            failWrite.countDown();
        }
    }
}
