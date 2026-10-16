package io.muserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static io.muserver.RFCTestUtils.*;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.*;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

@Timeout(30)
class Http2ReadDriverTest {
    private static byte[] preface() throws Exception {
        return preface(Http2Settings.DEFAULT_CLIENT_SETTINGS);
    }

    private static byte[] preface(Http2Settings settings) throws Exception {
        var out = new ByteArrayOutputStream();
        out.write("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(US_ASCII));
        settings.writeTo(null, out);
        Http2Settings.ACK.writeTo(null, out);
        return out.toByteArray();
    }

    private static byte[] requestBytes(int id) throws Exception {
        return headersFrame(id, true, true, encodeFieldBlock(getHelloHeaders("http", 80)));
    }

    @Test
    void everyTransportSplitAcrossPrefaceFramesAndContinuationCanResume() throws Exception {
        var handled = new AtomicInteger();
        byte[] block = encodeFieldBlock(getHelloHeaders("http", 80));
        var bytes = new ByteArrayOutputStream();
        bytes.write(preface());
        bytes.write(headersFrame(1, true, false, Arrays.copyOfRange(block, 0, 3)));
        bytes.write(continuationFrame(1, true, Arrays.copyOfRange(block, 3, block.length)));
        bytes.write(requestBytes(3));
        byte[] wire = bytes.toByteArray();
        try (var fixture = new Fixture((req, res) -> { handled.incrementAndGet(); return true; })) {
            for (int split = 0; split <= wire.length; split++) {
                Session session = fixture.add(wire.length);
                session.offer(Arrays.copyOfRange(wire, 0, split));
                fixture.barrier(); // The partial read returns its only worker.
                session.offer(Arrays.copyOfRange(wire, split, wire.length));
                fixture.until(() -> session.connection.completedRequests() == 2);
                session.end();
                session.driver.completion().get(3, TimeUnit.SECONDS);
                assertTrue(session.connection.testProbe().streams().isEmpty());
                assertTrue(session.connection.testProbe().pendingSettingsAcks().isEmpty());
            }
            assertEquals((wire.length + 1) * 2, handled.get());
        }
    }

    @Test
    void eofAtEveryIncompleteTransportPositionRetiresTheReader() throws Exception {
        var bytes = new ByteArrayOutputStream();
        bytes.write(preface());
        byte[] block = encodeFieldBlock(getHelloHeaders("http", 80));
        bytes.write(headersFrame(1, true, false, Arrays.copyOfRange(block, 0, 3)));
        bytes.write(continuationFrame(1, true, Arrays.copyOfRange(block, 3, block.length)));
        byte[] wire = bytes.toByteArray();
        try (var fixture = new Fixture((req, res) -> { fail("Incomplete headers dispatched a request"); return true; })) {
            for (int cut = 0; cut < wire.length; cut++) {
                Session session = fixture.add(wire.length);
                session.offer(Arrays.copyOf(wire, cut));
                session.end();
                session.driver.completion().get(3, TimeUnit.SECONDS);
                assertTrue(session.closed);
                assertTrue(session.connection.activeRequests().isEmpty());
            }
        }
    }

    @Test
    void idlePartialAndSuspendedConnectionsShareOneInternalWorker() throws Exception {
        Queue<AsyncHandle> handles = new ConcurrentLinkedQueue<>();
        try (var fixture = new Fixture((req, res) -> { handles.add(req.handleAsync()); return true; })) {
            for (int i = 0; i < 128; i++) {
                Session session = fixture.add(1024);
                session.offer(preface());
            }
            fixture.barrier();
            byte[] first = requestBytes(1);
            for (Session session : fixture.sessions) session.offer(Arrays.copyOf(first, first.length - 1));
            fixture.barrier();
            assertTrue(handles.isEmpty());
            for (Session session : fixture.sessions) session.offer(new byte[]{first[first.length - 1]});
            fixture.until(() -> handles.size() == 128);
            fixture.barrier();
            for (Session session : fixture.sessions) assertFalse(session.driver.completion().isDone());
            AsyncHandle handle;
            while ((handle = handles.poll()) != null) handle.complete();
            fixture.until(() -> fixture.sessions.stream().allMatch(s -> s.connection.completedRequests() == 1));
            fixture.barrier();
            assertEquals(1, fixture.internalThreads.get());
            assertEquals(1, fixture.applicationThreads.get());
            for (Session session : fixture.sessions) session.end();
        }
    }

    @ParameterizedTest @ValueSource(ints = {1, 7, 17, 8192})
    void boundedInputCompactsLargePhysicalPayloadsAndPreservesFollowingFrames(int capacity) throws Exception {
        CompletableFuture<String> body = new CompletableFuture<>();
        try (var fixture = new Fixture((req, res) -> { body.complete(req.readBodyAsString()); return true; })) {
            Session session = fixture.add(capacity);
            var wire = new ByteArrayOutputStream();
            wire.write(preface());
            FieldBlock headers = getHelloHeaders("http", 80);
            headers.set(":method", "POST");
            wire.write(headersFrame(1, false, true, encodeFieldBlock(headers)));
            // Exercise a full-size payload, then an unrelated PING in the same feed.
            String text = "abcde".repeat(3276) + "tail";
            byte[] data = text.getBytes(US_ASCII);
            wire.write(rawFrame(Http2FrameType.DATA, 1, 1, data));
            wire.write(rawFrame(Http2FrameType.PING, 0, 0, new byte[]{1,2,3,4,5,6,7,8}));
            session.feed(wire.toByteArray(), fixture);
            assertEquals(text, body.get(3, TimeUnit.SECONDS));
            fixture.until(() -> session.connection.completedRequests() == 1);
            fixture.barrier();
            assertTrue(containsFrame(session.output.toByteArray(), Http2FrameType.PING, 1));
        }
    }

    @Test
    void rejectionOfAResumedReaderCancelsActiveWorkOffTheNotifyingThread() throws Exception {
        CompletableFuture<AsyncHandle> handle = new CompletableFuture<>();
        CompletableFuture<String> completionThread = new CompletableFuture<>();
        try (var fixture = new Fixture((req, res) -> {
            res.addCompletionListener(info -> completionThread.complete(Thread.currentThread().getName()));
            handle.complete(req.handleAsync()); return true;
        })) {
            Session session = fixture.add(1024);
            session.offer(preface());
            session.offer(requestBytes(1));
            handle.get(3, TimeUnit.SECONDS);
            fixture.barrier();
            fixture.internal.shutdown();
            assertTrue(fixture.internal.awaitTermination(3, TimeUnit.SECONDS));
            session.input.fail(new IOException("injected input failure"));
            session.driver.inputAvailable();
            session.driver.completion().get(3, TimeUnit.SECONDS);
            assertTrue(completionThread.get(3, TimeUnit.SECONDS).startsWith("h2-reader-application-"));
            assertTrue(session.connection.activeRequests().isEmpty());
        }
    }

    @Test
    void pendingHandshakeAndFinalResponseDoNotPublishCompletionBeforeDrain() throws Exception {
        CompletableFuture<Http2Connection> handled = new CompletableFuture<>();
        CompletableFuture<ResponseInfo> completed = new CompletableFuture<>();
        try (var fixture = new Fixture((req, res) -> {
            res.addCompletionListener(completed::complete);
            res.status(204);
            handled.complete((Http2Connection) req.connection());
            return true;
        })) {
            Session session = fixture.add(1024, true);
            long before = fixture.server.stats().bytesSent();
            session.offer(preface());
            session.offer(requestBytes(1));
            fixture.until(() -> !session.pendingOutput.isEmpty());
            fixture.barrier();
            for (int i = 0; i < 1000; i++) session.driver.inputAvailable();
            fixture.barrier();
            assertFalse(handled.isDone(), "Queued input must wait for the initial SETTINGS to drain");
            assertEquals(before, fixture.server.stats().bytesSent());
            assertTrue(session.connection.testProbe().pendingSettingsAcks().isEmpty());
            session.completeOutput();
            handled.get(3, TimeUnit.SECONDS);
            fixture.until(() -> !session.pendingOutput.isEmpty());
            fixture.internal.submit(() -> {}).get(3, TimeUnit.SECONDS);
            assertFalse(completed.isDone());
            assertEquals(before + 48, fixture.server.stats().bytesSent());
            session.end(); // The in-flight final response retains its own output outcome.
            fixture.internal.submit(() -> {}).get(3, TimeUnit.SECONDS);
            assertFalse(session.driver.completion().isDone());
            session.completeOutput();
            assertTrue(completed.get(3, TimeUnit.SECONDS).completedSuccessfully());
            session.driver.completion().get(3, TimeUnit.SECONDS);
            assertEquals(before + session.output.size(), fixture.server.stats().bytesSent());
        }
    }

    @Test
    void shutdownReleasesAStalledInitialSettingsWriteWithoutDispatchingBufferedHeaders() throws Exception {
        try (var fixture = new Fixture((req, res) -> { fail("Shutdown must prevent dispatch"); return true; })) {
            Session session = fixture.add(1024, true);
            long before = fixture.server.stats().bytesSent();
            session.offer(preface());
            session.offer(requestBytes(1));
            fixture.until(() -> !session.pendingOutput.isEmpty());
            fixture.barrier();
            session.connection.forceShutdown();
            session.driver.completion().get(3, TimeUnit.SECONDS);
            assertTrue(session.closed);
            assertTrue(session.pendingOutput.isEmpty());
            assertTrue(session.connection.testProbe().pendingSettingsAcks().isEmpty());
            assertEquals(before, fixture.server.stats().bytesSent());
        }
    }

    @ParameterizedTest @CsvSource({"1,false", "3,false", "70,false", "3,true", "70,true"})
    void readyAsyncFramesShareBoundedWritesAndTheirTransportOutcome(int taskCount, boolean fail) throws Exception {
        try (var fixture = new Fixture((req, res) -> true)) {
            Session session = fixture.add(1024, true);
            session.offer(preface());
            fixture.until(() -> !session.pendingOutput.isEmpty());
            session.completeOutput();
            fixture.barrier();
            var release = new CountDownLatch(1);
            fixture.internal.submit(() -> { release.await(); return null; });
            var tasks = new ArrayList<WriteTask>();
            try {
                for (int i = 0; i < taskCount; i++) {
                    var task = new WriteTask(new Http2Ping(true, new byte[]{(byte) i, 0, 0, 0, 0, 0, 0, 0}), true);
                    tasks.add(task);
                    session.connection.write(task);
                }
            } finally { release.countDown(); }
            for (int start = 0; start < taskCount; start += 64) {
                fixture.until(() -> !session.pendingOutput.isEmpty());
                fixture.barrier();
                int count = Math.min(64, taskCount - start);
                assertEquals(count * 17, session.pendingOutput.peek().bytes.remaining());
                for (int i = start; i < taskCount; i++) assertFalse(tasks.get(i).completion().isDone());
                if (fail) {
                    session.failOutput();
                    for (var task : tasks) assertThrows(ExecutionException.class,
                        () -> task.completion().get(3, TimeUnit.SECONDS));
                    break;
                }
                session.completeOutput();
                for (int i = start; i < start + count; i++) tasks.get(i).completion().get(3, TimeUnit.SECONDS);
            }
            fixture.barrier();
            assertTrue(session.pendingOutput.isEmpty());
            if (!fail) {
                byte[] wire = session.output.toByteArray();
                for (int i = 0; i < taskCount; i++) assertEquals((byte) i, wire[48 + i * 17 + 9]);
            }
        }
    }

    @Test
    void asyncBatchFlushesReadyFramesBeforeWaitingForAnotherStreamsCredit() throws Exception {
        try (var fixture = new Fixture((req, res) -> true)) {
            Session session = fixture.add(1024, true);
            session.offer(preface());
            fixture.until(() -> !session.pendingOutput.isEmpty());
            session.completeOutput();
            fixture.barrier();
            var release = new CountDownLatch(1);
            fixture.internal.submit(() -> { release.await(); return null; });
            var coordinator = session.connection.testProbe().coordinator();
            var fields = new FieldBlock(); fields.set(":status", "200");
            var headers = new WriteTask(new Http2HeadersFrame(1, false, fields), true);
            var body = new WriteTask(new Http2DataFrame(1, true, new byte[]{42}, 0, 1), true);
            var ping = new WriteTask(new Http2Ping(true, new byte[8]), true);
            try {
                coordinator.openStream(1, 0);
                session.connection.write(headers);
                session.connection.write(body);
                session.connection.write(ping);
            } finally { release.countDown(); }
            fixture.until(() -> !session.pendingOutput.isEmpty());
            fixture.barrier();
            ByteBuffer encoded = session.pendingOutput.peek().bytes.duplicate();
            byte[] bytes = new byte[encoded.remaining()]; encoded.get(bytes);
            assertTrue(containsFrame(bytes, Http2FrameType.HEADERS, 4));
            assertTrue(containsFrame(bytes, Http2FrameType.PING, 1));
            assertFalse(headers.completion().isDone());
            assertFalse(ping.completion().isDone());
            session.completeOutput();
            headers.completion().get(3, TimeUnit.SECONDS);
            ping.completion().get(3, TimeUnit.SECONDS);
            fixture.barrier();
            assertTrue(session.pendingOutput.isEmpty());
            assertFalse(body.completion().isDone());
            coordinator.applyStreamWindowUpdate(1, 1);
            fixture.until(() -> !session.pendingOutput.isEmpty());
            assertEquals(10, session.pendingOutput.peek().bytes.remaining());
            session.completeOutput();
            body.completion().get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void completingABatchPrefixDoesNotRecycleTheBorrowedEncoding() throws Exception {
        try (var fixture = new Fixture((req, res) -> true)) {
            Session session = fixture.add(1024, true);
            session.offer(preface());
            fixture.until(() -> !session.pendingOutput.isEmpty());
            session.completeOutput();
            fixture.barrier();
            var release = new CountDownLatch(1);
            fixture.internal.submit(() -> { release.await(); return null; });
            var tasks = new ArrayList<WriteTask>();
            try {
                for (int i = 0; i < 64; i++) {
                    var task = new WriteTask(new Http2Ping(true, new byte[]{(byte) i, 0, 0, 0, 0, 0, 0, 0}), true);
                    tasks.add(task);
                    session.connection.write(task);
                }
            } finally { release.countDown(); }
            fixture.until(() -> !session.pendingOutput.isEmpty());
            session.advanceOutput(17);
            tasks.get(0).completion().get(3, TimeUnit.SECONDS);
            ByteBuffer borrowed = session.pendingOutput.peek().bytes.duplicate();
            byte[] expected = new byte[borrowed.remaining()];
            borrowed.duplicate().get(expected);
            for (int i = 0; i < 100; i++) {
                var competing = new Http2FrameBuffer();
                byte[] bytes = new byte[2048];
                Arrays.fill(bytes, (byte) 99);
                competing.write(bytes);
                competing.release();
            }
            byte[] retained = new byte[borrowed.remaining()];
            borrowed.get(retained);
            assertArrayEquals(expected, retained);
            for (int i = 1; i < tasks.size(); i++) assertFalse(tasks.get(i).completion().isDone());
            session.completeOutput();
            for (var task : tasks) task.completion().get(3, TimeUnit.SECONDS);
        }
    }

    @ParameterizedTest @CsvSource({"false,false,false", "true,false,false", "false,true,false", "true,true,false",
        "false,true,true", "true,true,true"})
    void terminalFramesDoNotWaitForLaterOutputToReleaseTheirStream(boolean body, boolean failLater, boolean failBeforeWriterRuns) throws Exception {
        var started = new CompletableFuture<Http2Stream>();
        try (var fixture = new Fixture((req, res) -> {
            req.handleAsync();
            ((BaseResponse) res).setState(ResponseState.WRITING_HEADERS);
            started.complete(((Http2Connection) req.connection()).testProbe().streams().applicationStream(1));
            return true;
        })) {
            Session session = fixture.add(1024, true);
            session.offer(preface());
            fixture.until(() -> !session.pendingOutput.isEmpty());
            session.completeOutput();
            session.offer(requestBytes(1));
            Http2Stream stream = started.get(3, TimeUnit.SECONDS);
            fixture.barrier();
            var release = new CountDownLatch(1);
            fixture.internal.submit(() -> { release.await(); return null; });
            var fields = new FieldBlock(); fields.set(":status", "200");
            var headers = new WriteTask(new Http2HeadersFrame(1, !body, fields), true);
            var terminal = body ? new WriteTask(Http2DataFrame.eos(1), true) : headers;
            var later = new WriteTask(new Http2Ping(true, new byte[8]), true);
            try {
                session.connection.write(headers);
                if (body) session.connection.write(terminal);
                session.connection.write(later);
            } finally { release.countDown(); }
            fixture.until(() -> !session.pendingOutput.isEmpty());
            fixture.barrier();
            ByteBuffer encoded = session.pendingOutput.peek().bytes.duplicate();
            Http2FrameHeader last;
            do {
                last = Http2FrameHeader.readFrom(encoded);
                encoded.position(encoded.position() + last.length());
            } while ((last.flags() & 1) == 0);
            assertEquals(body ? Http2FrameType.DATA : Http2FrameType.HEADERS, last.frameType());
            assertEquals(1, last.flags() & 1);
            assertEquals(17, encoded.remaining(), "The later PING shares the same transport write");
            assertTrue(stream.countsAsConcurrentProtocolStream());
            assertFalse(terminal.completion().isDone());
            assertFalse(later.completion().isDone());
            session.advanceOutput(encoded.position() - 1);
            fixture.barrier();
            assertTrue(stream.countsAsConcurrentProtocolStream(), "A partial terminal frame must retain admission");
            assertFalse(terminal.completion().isDone());
            var resumeWriter = new CountDownLatch(1);
            if (failBeforeWriterRuns) fixture.internal.submit(() -> { resumeWriter.await(); return null; });
            try {
                session.advanceOutput(1);
                if (failBeforeWriterRuns) session.failOutput();
            } finally { resumeWriter.countDown(); }
            terminal.completion().get(3, TimeUnit.SECONDS);
            assertFalse(stream.countsAsConcurrentProtocolStream());
            if (!failBeforeWriterRuns) {
                assertEquals(17, session.pendingOutput.peek().bytes.remaining());
                assertFalse(later.completion().isDone());
            }
            if (failLater) {
                if (!failBeforeWriterRuns) session.failOutput();
                assertThrows(ExecutionException.class, () -> later.completion().get(3, TimeUnit.SECONDS));
                terminal.completion().get(3, TimeUnit.SECONDS);
            } else {
                session.completeOutput();
                later.completion().get(3, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void asynchronousStreamWriteResumesAcrossCreditAndWaitsForTheFinalTransportAcknowledgement() throws Exception {
        CompletableFuture<Http2Stream> started = new CompletableFuture<>();
        CompletableFuture<AsyncHandle> handle = new CompletableFuture<>();
        try (var fixture = new Fixture((req, res) -> {
            handle.complete(req.handleAsync());
            ((BaseResponse) res).setState(ResponseState.WRITING_HEADERS);
            started.complete(((Http2Connection) req.connection()).testProbe().streams().applicationStream(1));
            return true;
        })) {
            Session session = fixture.add(1024, true);
            session.offer(preface(new Http2Settings(false, 4096, 100, 1, 16384, 32768)));
            session.offer(requestBytes(1));
            fixture.until(() -> !session.pendingOutput.isEmpty());
            session.completeOutput();
            Http2Stream stream = started.get(3, TimeUnit.SECONDS);
            var fields = new FieldBlock();
            fields.set(":status", "200");
            var headers = stream.writeAsynchronously(new Http2HeadersFrame(1, false, fields));
            fixture.until(() -> !session.pendingOutput.isEmpty());
            session.completeOutput();
            headers.get(3, TimeUnit.SECONDS);
            var written = stream.writeAsynchronously(new Http2DataFrame(1, true, new byte[]{11, 22}, 0, 2));
            fixture.until(() -> !session.pendingOutput.isEmpty());
            fixture.barrier();
            assertFalse(written.isDone());
            assertThrows(IllegalStateException.class, () -> stream.writeAsynchronously(Http2DataFrame.eos(1)));
            Http2FrameHeader first = Http2FrameHeader.readFrom(session.pendingOutput.peek().bytes.duplicate());
            assertEquals(1, first.length());
            assertEquals(0, first.flags());
            session.completeOutput();
            fixture.barrier();
            assertFalse(written.isDone(), "The logical write still owns its credit-blocked remainder");
            assertTrue(session.pendingOutput.isEmpty());
            session.offer(rawFrame(Http2FrameType.WINDOW_UPDATE, 0, 1, new byte[]{0, 0, 0, 1}));
            fixture.until(() -> !session.pendingOutput.isEmpty());
            fixture.barrier();
            Http2FrameHeader last = Http2FrameHeader.readFrom(session.pendingOutput.peek().bytes.duplicate());
            assertEquals(1, last.length());
            assertEquals(1, last.flags());
            assertFalse(written.isDone());
            session.end();
            fixture.barrier();
            assertFalse(written.isDone(), "Input EOF cannot acknowledge the final output borrow");
            session.completeOutput();
            written.get(3, TimeUnit.SECONDS);
            handle.get().complete();
            session.driver.completion().get(3, TimeUnit.SECONDS);
            assertEquals(1, session.connection.completedRequests());
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void resetOfAnActiveFragmentRetainsTheNativeWriteUntilTransportAbortAcknowledges(boolean peerReset) throws Exception {
        CompletableFuture<Http2Stream> started = new CompletableFuture<>();
        try (var fixture = new Fixture((req, res) -> {
            req.handleAsync();
            ((BaseResponse) res).setState(ResponseState.WRITING_HEADERS);
            started.complete(((Http2Connection) req.connection()).testProbe().streams().applicationStream(1));
            return true;
        })) {
            Session session = fixture.add(1024, true);
            session.offer(preface(new Http2Settings(false, 4096, 100, 1, 16384, 32768)));
            session.offer(requestBytes(1));
            fixture.until(() -> !session.pendingOutput.isEmpty());
            session.completeOutput();
            Http2Stream stream = started.get(3, TimeUnit.SECONDS);
            var written = stream.writeAsynchronously(new Http2DataFrame(1, true, new byte[]{11, 22}, 0, 2));
            fixture.until(() -> !session.pendingOutput.isEmpty());
            session.holdAbortedOutput = true;
            if (peerReset) session.offer(rawFrame(Http2FrameType.RST_STREAM, 0, 1, new byte[]{0, 0, 0, 8}));
            else stream.abortOutput(new IOException("local cancellation"));
            fixture.until(() -> session.closed);
            fixture.barrier();
            assertFalse(written.isDone());
            assertFalse(session.driver.completion().isDone());
            session.holdAbortedOutput = false;
            session.failOutput();
            assertInstanceOf(IOException.class, assertThrows(ExecutionException.class,
                () -> written.get(3, TimeUnit.SECONDS)).getCause());
            session.driver.completion().get(3, TimeUnit.SECONDS);
            assertThrows(IOException.class, () -> stream.writeAsynchronously(Http2DataFrame.eos(1)));
        }
    }

    @Test
    void malformedPrefaceDrainsItsGoAwayBeforeRetirement() throws Exception {
        try (var fixture = new Fixture((req, res) -> { fail("Malformed preface dispatched a request"); return true; })) {
            Session session = fixture.add(1024, true);
            session.offer(Arrays.copyOf(preface(), 24));
            session.offer(rawFrame(Http2FrameType.PING, 0, 0, new byte[8]));
            fixture.until(() -> !session.pendingOutput.isEmpty());
            fixture.barrier();
            assertFalse(session.closed);
            assertFalse(session.driver.completion().isDone());
            session.completeOutput();
            session.driver.completion().get(3, TimeUnit.SECONDS);
            ByteBuffer bytes = ByteBuffer.wrap(session.output.toByteArray());
            Http2FrameHeader header = Http2FrameHeader.readFrom(bytes);
            assertEquals(Http2FrameType.GOAWAY, header.frameType());
            assertEquals(Http2ErrorCode.PROTOCOL_ERROR.code(), Http2GoAway.readFrom(header, bytes).errorCode());
        }
    }

    @ParameterizedTest @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void responseFinalizationRetainsItsOutcomeUntilTheLastTransportAcknowledgement(boolean body, boolean fail) throws Exception {
        var response = new CompletableFuture<BaseResponse>();
        var notified = new CompletableFuture<ResponseInfo>();
        try (var fixture = new Fixture((request, res) -> {
            response.complete((BaseResponse) res);
            res.addCompletionListener(notified::complete);
            if (body) res.outputStream(8192).write(new byte[]{11, 22});
            else res.status(204);
            return true;
        })) {
            Session session = fixture.add(1024, true);
            session.offer(preface());
            fixture.until(() -> !session.pendingOutput.isEmpty());
            session.completeOutput();
            session.offer(requestBytes(1));
            fixture.until(() -> !session.pendingOutput.isEmpty());
            if (body) {
                Http2FrameHeader initial = Http2FrameHeader.readFrom(session.pendingOutput.peek().bytes.duplicate());
                assertEquals(Http2FrameType.HEADERS, initial.frameType());
                assertEquals(0, initial.flags() & 1);
                session.completeOutput();
                fixture.until(() -> !session.pendingOutput.isEmpty());
            }
            fixture.barrier();
            Http2FrameHeader terminal = Http2FrameHeader.readFrom(session.pendingOutput.peek().bytes.duplicate());
            assertEquals(body ? Http2FrameType.DATA : Http2FrameType.HEADERS, terminal.frameType());
            assertEquals(1, terminal.flags() & 1);
            assertEquals(0, session.connection.completedRequests());
            assertFalse(response.get().responseState().endState());
            assertFalse(notified.isDone());
            session.end();
            fixture.barrier();
            assertFalse(session.driver.completion().isDone());
            assertFalse(notified.isDone());
            if (fail) session.failOutput(); else session.completeOutput();
            session.driver.completion().get(3, TimeUnit.SECONDS);
            assertEquals(!fail, notified.get(3, TimeUnit.SECONDS).completedSuccessfully());
            assertEquals(1, session.connection.completedRequests());
        }
    }

    private static byte[] rawFrame(Http2FrameType type, int flags, int id, byte[] payload) {
        return ByteBuffer.allocate(9 + payload.length).put((byte) (payload.length >> 16))
            .put((byte) (payload.length >> 8)).put((byte) payload.length).put(type.byteCode())
            .put((byte) flags).putInt(id).put(payload).array();
    }

    private static boolean containsFrame(byte[] bytes, Http2FrameType type, int flags) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.remaining() >= 9) {
            int length = ((buffer.get() & 255) << 16) | ((buffer.get() & 255) << 8) | (buffer.get() & 255);
            byte actualType = buffer.get(), actualFlags = buffer.get();
            buffer.getInt();
            if (actualType == type.byteCode() && actualFlags == flags) return true;
            buffer.position(buffer.position() + length);
        }
        return false;
    }

    @ParameterizedTest @CsvSource({"false,8192", "true,8192", "false,16384", "true,16384"})
    void aHandlerFailureJoinsItsOutstandingAsyncWriteBeforeRetiring(boolean completeFirst, int size) throws Exception {
        var releaseHandler = new CountDownLatch(1);
        var written = new CompletableFuture<java.util.concurrent.Future<@org.jspecify.annotations.Nullable Void>>();
        var notified = new CompletableFuture<ResponseInfo>();
        try (var fixture = new Fixture((request, response) -> {
            response.addCompletionListener(notified::complete);
            var async = request.handleAsync();
            written.complete(async.write(ByteBuffer.wrap(new byte[size]).asReadOnlyBuffer()));
            if (completeFirst) async.complete();
            releaseHandler.await();
            throw new IOException("Controlled handler failure");
        })) {
            Session session = fixture.add(1024, true);
            session.offer(preface());
            fixture.until(() -> !session.pendingOutput.isEmpty());
            session.completeOutput();
            session.offer(requestBytes(1));
            fixture.until(() -> !session.pendingOutput.isEmpty());
            session.completeOutput(); // Response headers precede the borrowed DATA fragment.
            fixture.until(() -> !session.pendingOutput.isEmpty());
            ByteBuffer borrowed = session.pendingOutput.peek().bytes.duplicate();
            byte[] expected = new byte[borrowed.remaining()];
            borrowed.duplicate().get(expected);
            session.holdAbortedOutput = true;
            releaseHandler.countDown();
            fixture.until(() -> session.closed);
            fixture.barrier();
            assertEquals(0, session.connection.completedRequests(), "The transport still owns the output buffer");
            assertFalse(notified.isDone());
            assertFalse(written.get().isDone());
            // Other connections can recycle every idle scratch lease while cancellation is
            // pending. This transport's encoding must remain unchanged until its own ACK.
            var other = new Http2FrameBuffer();
            byte[] overwrite = new byte[8192];
            Arrays.fill(overwrite, (byte) 99);
            for (int i = 0; i < 100; i++) { other.write(overwrite); other.release(); }
            byte[] retained = new byte[borrowed.remaining()];
            borrowed.get(retained);
            assertArrayEquals(expected, retained);
            session.holdAbortedOutput = false;
            session.failOutput();
            assertThrows(java.util.concurrent.ExecutionException.class, () -> written.get().get(3, TimeUnit.SECONDS));
            session.driver.completion().get(3, TimeUnit.SECONDS);
            assertFalse(notified.get(3, TimeUnit.SECONDS).completedSuccessfully());
            assertEquals(1, session.connection.completedRequests());
        } finally { releaseHandler.countDown(); }
    }

    private static final class Fixture implements AutoCloseable {
        final AtomicInteger internalThreads = new AtomicInteger(), applicationThreads = new AtomicInteger();
        final ExecutorService internal = Executors.newSingleThreadExecutor(r -> new Thread(r, "h2-reader-internal-" + internalThreads.incrementAndGet()));
        final ExecutorService application = Executors.newSingleThreadExecutor(r -> new Thread(r, "h2-reader-application-" + applicationThreads.incrementAndGet()));
        final List<Session> sessions = new ArrayList<>();
        final MuServer server;
        final BaseHttpConnection config;
        Fixture(MuHandler handler) throws Exception {
            var captured = new AtomicReference<BaseHttpConnection>();
            server = TestExecutionResources.configure(MuServerBuilder.httpServer(), null, null, internal, null)
                .withHandlerExecutor(application).addHandler((req, res) -> {
                    if (req.uri().getPath().equals("/fixture")) {
                        captured.set((BaseHttpConnection) req.connection()); res.write("ready"); return true;
                    }
                    return handler.handle(req, res);
                }).start();
            try (var response = call(request(server.uri().resolve("/fixture")).header("Connection", "close"))) {
                assertEquals("ready", response.body().string());
            }
            config = captured.get();
            barrier();
        }
        Session add(int capacity) { return add(capacity, false); }
        Session add(int capacity, boolean delayed) {
            Session result = new Session(this, capacity, delayed); sessions.add(result); return result;
        }
        void barrier() throws Exception {
            internal.submit(() -> {}).get(3, TimeUnit.SECONDS);
            application.submit(() -> {}).get(3, TimeUnit.SECONDS);
            internal.submit(() -> {}).get(3, TimeUnit.SECONDS);
        }
        void until(BooleanSupplier ready) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!ready.getAsBoolean()) {
                assertTrue(System.nanoTime() < deadline, "Protocol progression timed out");
                Thread.sleep(1);
            }
        }
        @Override public void close() throws Exception {
            try {
                for (Session session : sessions) {
                    session.holdAbortedOutput = false;
                    session.failOutput();
                    session.connection.forceShutdown();
                    session.driver.inputAvailable();
                }
                for (Session session : sessions) session.driver.completion().get(3, TimeUnit.SECONDS);
            } finally {
                server.stop();
                internal.shutdownNow(); application.shutdownNow();
                assertTrue(internal.awaitTermination(3, TimeUnit.SECONDS));
                assertTrue(application.awaitTermination(3, TimeUnit.SECONDS));
            }
        }
    }

    private static final class Session implements ConnectionTransport {
        final Semaphore capacity = new Semaphore(0);
        final TransportInputBuffer input;
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final Queue<PendingOutput> pendingOutput = new ConcurrentLinkedQueue<>();
        final Http2Connection connection;
        final Http2Connection.ReadDriver driver;
        volatile boolean closed;
        volatile boolean holdAbortedOutput;
        Session(Fixture fixture, int capacity, boolean delayed) {
            input = new TransportInputBuffer(capacity, this.capacity::release);
            connection = new Http2Connection(fixture.config.server, fixture.config.creator, this,
                ConnectionAcceptedTime.now(), null, Http2Settings.DEFAULT_CLIENT_SETTINGS, 30000,
                fixture.application, fixture.internal);
            driver = connection.readDriver(input, new AsyncTransportOutput() {
                @Override public CompletableFuture<@org.jspecify.annotations.Nullable Void> write(ByteBuffer source) throws IOException {
                    return write(source, new TransportWriteProgress(() -> {}));
                }
                @Override public CompletableFuture<@org.jspecify.annotations.Nullable Void> write(ByteBuffer source, TransportWriteProgress progress) throws IOException {
                if (delayed) {
                    var write = new PendingOutput(source, progress);
                    pendingOutput.add(write);
                    return write.completion;
                }
                byte[] bytes = new byte[source.remaining()];
                source.duplicate().get(bytes);
                output.write(bytes);
                progress.consumed(bytes.length);
                return CompletableFuture.completedFuture(null);
                }
            });
        }
        void advanceOutput(int count) {
            PendingOutput write = java.util.Objects.requireNonNull(pendingOutput.peek());
            byte[] bytes = new byte[count];
            write.bytes.get(bytes);
            output.writeBytes(bytes);
            write.progress.consumed(count);
            write.progress.notifyOwner();
        }
        void completeOutput() {
            PendingOutput write = java.util.Objects.requireNonNull(pendingOutput.peek());
            advanceOutput(write.bytes.remaining());
            pendingOutput.remove(write);
            write.completion.complete(null);
        }
        void offer(byte[] bytes) throws IOException {
            assertEquals(bytes.length, input.offer(ByteBuffer.wrap(bytes)));
            connection.onBytesRead(bytes.length);
            driver.inputAvailable();
        }
        void feed(byte[] bytes, Fixture fixture) throws Exception {
            ByteBuffer source = ByteBuffer.wrap(bytes);
            while (source.hasRemaining()) {
                int count = input.offer(source);
                connection.onBytesRead(count);
                driver.inputAvailable();
                if (count == 0) assertTrue(capacity.tryAcquire(3, TimeUnit.SECONDS), "Input reader did not return capacity");
            }
        }
        void end() { input.endOfInput(); driver.inputAvailable(); }
        @Override public InetSocketAddress remoteAddress() { return new InetSocketAddress("192.0.2.1", 1234); }
        @Override public InetSocketAddress localAddress() { return new InetSocketAddress("127.0.0.1", 80); }
        @Override public boolean isSecure() { return false; }
        @Override public String tlsProtocol() { return null; }
        @Override public String cipherSuite() { return null; }
        @Override public String sniHostName() { return null; }
        @Override public Certificate clientCertificate() { return null; }
        @Override public void readTimeoutMillis(int timeoutMillis) { input.readTimeoutMillis(timeoutMillis); }
        @Override public void shutdownInput() { end(); }
        @Override public void close() { closed = true; end(); }
        @Override public void abort() {
            closed = true;
            IOException failure = new IOException("Transport aborted");
            input.fail(failure);
            if (!holdAbortedOutput) failOutput();
            driver.inputAvailable();
        }
        void failOutput() {
            PendingOutput pending;
            while ((pending = pendingOutput.poll()) != null) {
                pending.completion.completeExceptionally(new IOException("Transport aborted"));
            }
        }
    }

    private static final class PendingOutput {
        final ByteBuffer bytes;
        final TransportWriteProgress progress;
        final CompletableFuture<Void> completion = new CompletableFuture<>();
        PendingOutput(ByteBuffer bytes, TransportWriteProgress progress) { this.bytes = bytes.duplicate(); this.progress = progress; }
    }
}
