package io.muserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class WebsocketFrameWriterTest {
    @Test
    void failureIsPublishedBeforeReturningOwnershipToTheWriteCaller() throws Exception {
        try (var fixture = new Fixture(7)) {
            IOException expected = new IOException("Write failed before receive callback returns");
            var write = fixture.writer.write(2, true, false, ByteBuffer.wrap(new byte[]{42}));
            var released = write.handle((ignored, failure) -> {
                assertSame(expected, failure);
                assertSame(expected, fixture.errors.peek(), "A receive callback must not overtake write-error publication");
                return null;
            });
            fixture.awaitWithoutDraining(() -> fixture.output.pendingBytes() > 0);
            fixture.output.fail(expected);
            released.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void automaticPingIsGeneratedAtDequeueAndSkippedAfterClose() throws Exception {
        try (var fixture = new Fixture(1)) {
            var generated = new AtomicInteger();
            var data = fixture.writer.write(2, true, false, ByteBuffer.wrap(new byte[]{42}));
            fixture.awaitWithoutDraining(() -> fixture.output.pendingBytes() > 0);
            var ping = fixture.writer.automaticPing(() -> ByteBuffer.wrap(new byte[]{(byte) generated.incrementAndGet()}));
            fixture.barrier();
            assertEquals(0, generated.get(), "Queueing time must not enter the latency measurement");
            fixture.until(ping::isDone);
            data.get(); ping.get();
            assertEquals(1, generated.get());
            var close = fixture.writer.write(8, true, false, ByteBuffer.allocate(0));
            var latePing = fixture.writer.automaticPing(() -> fixture.closed.get() == 0 ? ByteBuffer.allocate(0) : null);
            fixture.until(latePing::isDone);
            close.get(); latePing.get();
            assertArrayEquals(new byte[]{(byte) 0x82, 1, 42, (byte) 0x89, 1, 1, (byte) 0x88, 0}, fixture.sink.bytes.toByteArray());
            assertTrue(fixture.errors.isEmpty());
        }
    }

    @Test
    void zeroProgressDoesNotAcknowledgeBytesAndInvalidCloseDoesNotOwnTheNextClose() throws Exception {
        try (var fixture = new Fixture(7)) {
            var invalid = fixture.writer.write(8, true, false, ByteBuffer.allocate(126));
            fixture.awaitWithoutDraining(invalid::isDone);
            assertTrue(invalid.isCompletedExceptionally());
            var close = fixture.writer.write(8, true, false, ByteBuffer.wrap(new byte[]{3, (byte) 232}));
            assertSame(close, fixture.writer.closeCompletion());
            fixture.awaitWithoutDraining(() -> fixture.output.pendingBytes() > 0);
            fixture.sink.limit = 0;
            for (int i = 0; i < 4; i++) assertEquals(0, fixture.output.drainTo(fixture.sink, 8192));
            fixture.barrier();
            assertFalse(close.isDone());
            assertEquals(0, fixture.sent.get());
            assertEquals(0, fixture.closed.get());
            fixture.sink.limit = 7;
            fixture.until(close::isDone);
            close.get();
            assertEquals(4, fixture.sent.get());
            assertEquals(1, fixture.closed.get());
        }
    }

    @ParameterizedTest @ValueSource(ints = {1, 7, 8192})
    void partialDrainsPreserveHeadersPayloadsAndCallerBufferPositions(int capacity) throws Exception {
        try (var fixture = new Fixture(capacity)) {
            var expected = new ByteArrayOutputStream();
            for (int length : new int[]{0, 1, 125, 126, 65535, 65536}) {
                byte[] bytes = new byte[length];
                for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i * 31);
                for (int kind = 0; kind < 3; kind++) {
                    ByteBuffer source = kind == 0 ? ByteBuffer.wrap(bytes).asReadOnlyBuffer()
                        : kind == 1 ? ByteBuffer.allocateDirect(length).put(bytes).flip()
                        : ByteBuffer.allocate(length + 7).position(7).put(bytes).flip().position(7).slice();
                    var done = fixture.writer.write(2, true, false, source);
                    fixture.until(done::isDone);
                    done.get();
                    assertEquals(0, source.position());
                    assertEquals(length, source.remaining());
                    expected.write(frame(0x82, bytes));
                }
            }
            assertArrayEquals(expected.toByteArray(), fixture.sink.bytes.toByteArray());
            assertEquals(expected.size(), fixture.sent.get());
            assertEquals(0, fixture.errors.size());
        }
    }

    @Test
    void stalledPayloadReleasesWorkerAndFinishRetainsItsBorrowUntilTransportFailure() throws Exception {
        try (var fixture = new Fixture(7)) {
            var active = fixture.writer.write(2, true, false, ByteBuffer.wrap(new byte[1024]));
            fixture.until(() -> fixture.sent.get() == 4 && fixture.output.pendingBytes() > 0);
            var queued = fixture.writer.write(2, true, false, ByteBuffer.wrap(new byte[]{42}));
            fixture.barrier();
            assertFalse(active.isDone());
            assertFalse(queued.isDone());
            var ended = fixture.writer.finish();
            fixture.awaitWithoutDraining(queued::isDone);
            assertTrue(queued.isCompletedExceptionally());
            assertFalse(active.isDone());
            assertFalse(ended.isDone());
            fixture.barrier();
            fixture.output.fail(new IOException("transport released pending payload"));
            ended.get(3, TimeUnit.SECONDS);
            assertTrue(active.isCompletedExceptionally());
            assertEquals(4, fixture.sent.get(), "A partially sent payload is not a completed write");
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void asynchronousTransportFailureFailsTheQueueAndAllLaterWrites(boolean payload) throws Exception {
        try (var fixture = new Fixture(7)) {
            var first = fixture.writer.write(2, true, false, ByteBuffer.wrap(new byte[1024]));
            if (payload) fixture.until(() -> fixture.sent.get() == 4 && fixture.output.pendingBytes() > 0);
            else fixture.awaitWithoutDraining(() -> fixture.output.pendingBytes() > 0);
            var second = fixture.writer.write(1, true, false, ByteBuffer.wrap(new byte[]{'x'}));
            IOException failure = new IOException("sink failed");
            fixture.output.fail(failure);
            fixture.awaitWithoutDraining(() -> first.isDone() && second.isDone());
            assertSame(failure, assertThrows(java.util.concurrent.ExecutionException.class, first::get).getCause());
            assertSame(failure, assertThrows(java.util.concurrent.ExecutionException.class, second::get).getCause());
            var later = fixture.writer.write(9, true, false, ByteBuffer.allocate(0));
            assertSame(failure, assertThrows(java.util.concurrent.ExecutionException.class, later::get).getCause().getCause());
            assertEquals(1, fixture.errors.size());
            assertSame(failure, fixture.errors.peek());
        }
    }

    @Test
    void invalidQueuedCommandsDoNotPoisonTheActiveFragmentedMessage() throws Exception {
        try (var fixture = new Fixture(7)) {
            var first = fixture.writer.write(1, false, true, ByteBuffer.wrap(new byte[]{'a'}));
            var wrong = fixture.writer.write(2, true, true, ByteBuffer.wrap(new byte[]{'x'}));
            var ping = fixture.writer.write(9, true, false, ByteBuffer.wrap(new byte[]{7}));
            var last = fixture.writer.write(1, true, true, ByteBuffer.wrap(new byte[]{'b'}));
            fixture.until(last::isDone);
            first.get(); ping.get(); last.get();
            assertInstanceOf(IllegalStateException.class, assertThrows(java.util.concurrent.ExecutionException.class, wrong::get).getCause());
            var expected = new ByteArrayOutputStream();
            expected.write(frame(1, new byte[]{'a'}));
            expected.write(frame(0x89, new byte[]{7}));
            expected.write(frame(0x80, new byte[]{'b'}));
            assertArrayEquals(expected.toByteArray(), fixture.sink.bytes.toByteArray());
            assertEquals(0, fixture.errors.size());
        }
    }

    @Test
    void closeIsPublishedOnlyAfterPayloadDrainAndPendingCloseRequestsShareOneFrame() throws Exception {
        try (var fixture = new Fixture(1)) {
            var close = fixture.writer.write(8, true, false, ByteBuffer.wrap(new byte[]{3, (byte) 232}));
            fixture.awaitWithoutDraining(() -> fixture.closing.get() == 1);
            assertSame(close, fixture.writer.closeCompletion());
            assertSame(close, fixture.writer.write(8, true, false, ByteBuffer.allocate(0)));
            fixture.until(() -> fixture.sent.get() == 2 && fixture.output.pendingBytes() > 0);
            assertFalse(close.isDone());
            assertEquals(0, fixture.closed.get());
            fixture.until(close::isDone);
            close.get();
            assertEquals(1, fixture.closed.get());
            assertArrayEquals(frame(0x88, new byte[]{3, (byte) 232}), fixture.sink.bytes.toByteArray());
            var afterClose = fixture.writer.write(1, true, false, ByteBuffer.allocate(0));
            fixture.awaitWithoutDraining(afterClose::isDone);
            assertInstanceOf(IllegalStateException.class,
                assertThrows(java.util.concurrent.ExecutionException.class, afterClose::get).getCause());
            fixture.writer.finish().get(2, TimeUnit.SECONDS);
            var afterRetirement = fixture.writer.write(1, true, false, ByteBuffer.allocate(0));
            assertInstanceOf(IllegalStateException.class,
                assertThrows(java.util.concurrent.ExecutionException.class, afterRetirement::get).getCause());
        }
    }

    @Test
    void rejectedExecutorFailsPendingCommandsAndReportsFailureOffTheNotifier() throws Exception {
        try (var fixture = new Fixture(7)) {
            fixture.executor.shutdown();
            var write = fixture.writer.write(2, true, false, ByteBuffer.allocate(0));
            fixture.awaitWithoutDraining(write::isDone);
            assertTrue(write.isCompletedExceptionally());
            fixture.writer.finish().get(3, TimeUnit.SECONDS);
            assertEquals(1, fixture.errors.size());
            assertEquals("mu-websocket-rejected-writer", fixture.errorThread.get());
            assertEquals(0, fixture.sink.bytes.size());
        }
    }

    private static byte[] frame(int firstByte, byte[] payload) throws IOException {
        var bytes = new ByteArrayOutputStream();
        var output = new DataOutputStream(bytes);
        output.writeByte(firstByte);
        if (payload.length < 126) output.writeByte(payload.length);
        else if (payload.length <= 65535) { output.writeByte(126); output.writeShort(payload.length); }
        else { output.writeByte(127); output.writeLong(payload.length); }
        output.write(payload);
        return bytes.toByteArray();
    }

    private static final class Fixture implements AutoCloseable, WebsocketFrameWriter.Events {
        final ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "websocket-frame-writer"));
        final TransportOutputBuffer output;
        final WebsocketFrameWriter writer;
        final Sink sink = new Sink();
        final AtomicInteger sent = new AtomicInteger();
        final AtomicInteger closing = new AtomicInteger();
        final AtomicInteger closed = new AtomicInteger();
        final Queue<IOException> errors = new ConcurrentLinkedQueue<>();
        final AtomicReference<String> errorThread = new AtomicReference<>();
        Fixture(int capacity) throws IOException {
            output = new TransportOutputBuffer(capacity, () -> { });
            writer = new WebsocketFrameWriter(executor, output.asynchronousWriter(),
                () -> output.fail(new IOException("transport aborted")), this);
        }
        void barrier() throws Exception { executor.submit(() -> {}).get(2, TimeUnit.SECONDS); }
        void until(BooleanSupplier done) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (!done.getAsBoolean()) {
                assertTrue(System.nanoTime() < deadline, "Frame write did not progress");
                if (output.pendingBytes() == 0 || output.drainTo(sink, 8192) == 0) Thread.sleep(1);
            }
        }
        void awaitWithoutDraining(BooleanSupplier done) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!done.getAsBoolean()) {
                assertTrue(System.nanoTime() < deadline, "Frame writer did not resume");
                Thread.sleep(1);
            }
        }
        @Override public void bytesSent(int count) { sent.addAndGet(count); }
        @Override public void closeStarted() { closing.incrementAndGet(); }
        @Override public void closeWritten() { closed.incrementAndGet(); }
        @Override public void failed(IOException failure) { errorThread.set(Thread.currentThread().getName()); errors.add(failure); }
        @Override public void close() throws Exception {
            var ended = writer.finish();
            output.fail(new IOException("fixture closed"));
            try { ended.get(3, TimeUnit.SECONDS); }
            finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS)); }
        }
    }

    private static final class Sink implements WritableByteChannel {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int limit = 7;
        @Override public int write(ByteBuffer source) {
            int count = Math.min(limit, source.remaining());
            byte[] part = new byte[count];
            source.get(part);
            bytes.write(part, 0, part.length);
            return count;
        }
        @Override public boolean isOpen() { return true; }
        @Override public void close() { }
    }
}
