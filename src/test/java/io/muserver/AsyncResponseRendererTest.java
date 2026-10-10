package io.muserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class AsyncResponseRendererTest {
    @TempDir Path directory;

    @Test void oneWorkerRemainsAvailableDuringTransportBackpressure() throws Exception {
        var borrowed = new CompletableFuture<ByteBuffer>();
        var io = new CompletableFuture<Void>();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        var capture = new Http1ResponseOutput(OutputStream.nullOutputStream(), bytes -> {
            borrowed.complete(bytes); return io;
        }, directory);
        var renderer = new AsyncResponseRenderer(executor, capture, () -> capture);
        try {
            var written = executor.submit(() -> renderer.write(ByteBuffer.allocate(8 * 1024 * 1024)))
                .get(3, TimeUnit.SECONDS);
            assertEquals(8192, borrowed.get(3, TimeUnit.SECONDS).remaining());
            executor.submit(() -> {}).get(3, TimeUnit.SECONDS);
            assertFalse(written.isDone());
            IOException aborted = new IOException("transport aborted");
            io.completeExceptionally(aborted);
            assertSame(aborted, assertThrows(ExecutionException.class,
                () -> written.get(3, TimeUnit.SECONDS)).getCause());
        } finally { io.completeExceptionally(new IOException("test closed")); executor.shutdownNow(); }
    }

    @ParameterizedTest @ValueSource(ints = {0, 1, 2, 3})
    void heapDirectReadOnlyAndSlicedSourcesPreserveTheirAdapterPositionSemantics(int kind) throws Exception {
        Queue<Runnable> tasks = new ArrayDeque<>();
        var wire = new ByteArrayOutputStream();
        var capture = new Http1ResponseOutput(OutputStream.nullOutputStream(), bytes -> {
            byte[] copy = new byte[bytes.remaining()]; bytes.duplicate().get(copy); wire.write(copy);
            return CompletableFuture.completedFuture(null);
        }, directory);
        byte[] expected = new byte[20000];
        new java.util.Random(4).nextBytes(expected);
        ByteBuffer input = kind == 1 ? ByteBuffer.allocateDirect(expected.length + 8)
            : ByteBuffer.allocate(expected.length + 8);
        input.position(4); input.put(expected); input.flip(); input.position(4);
        if (kind == 2) input = input.asReadOnlyBuffer();
        if (kind == 3) input = input.slice();
        int position = input.position();
        var result = new AsyncResponseRenderer(tasks::add, capture, () -> capture).write(input);
        runTasks(tasks);
        result.get(3, TimeUnit.SECONDS);
        assertArrayEquals(expected, wire.toByteArray());
        assertEquals(input.hasArray() ? position : input.limit(), input.position());
    }

    @Test void anEncoderBurstUsesOwnedStorageAndDrainsBeforeItsFailureIsPublished() throws Exception {
        var tasks = new ArrayDeque<Runnable>();
        var pending = new ArrayDeque<CompletableFuture<Void>>();
        var borrowed = new ArrayDeque<ByteBuffer>();
        byte[] expected = new byte[1024 * 1024];
        new java.util.Random(42).nextBytes(expected);
        var capture = new Http1ResponseOutput(OutputStream.nullOutputStream(), bytes -> {
            var io = new CompletableFuture<Void>(); pending.add(io); borrowed.add(bytes); return io;
        }, directory);
        IOException failure = new IOException("encoder failed after producing its prefix");
        var encoder = new OutputStream() {
            @Override public void write(int value) throws IOException {
                byte[] scratch = expected.clone();
                capture.write(scratch);
                java.util.Arrays.fill(scratch, (byte) 0);
                throw failure;
            }
        };
        var written = new AsyncResponseRenderer(tasks::add, capture, () -> encoder).write(ByteBuffer.wrap(new byte[]{1}));
        var actual = new ByteArrayOutputStream();
        while (!pending.isEmpty()) {
            assertFalse(written.isDone());
            ByteBuffer bytes = borrowed.remove();
            assertTrue(bytes.remaining() <= 8192);
            byte[] copy = new byte[bytes.remaining()]; bytes.get(copy); actual.write(copy);
            pending.remove().complete(null);
            runTasks(tasks);
        }
        assertSame(failure, assertThrows(CompletionException.class, written::join).getCause());
        assertArrayEquals(expected, actual.toByteArray());
        assertNoTemporaryFiles();
    }

    @Test void aTurnBoundaryCannotReleaseAPendingBorrowWhenContinuationDispatchRejects() throws Exception {
        var io = new CompletableFuture<Void>();
        var calls = new AtomicInteger();
        var rejected = new RejectedExecutionException("no more workers");
        var capture = new Http1ResponseOutput(OutputStream.nullOutputStream(), bytes -> {
            return calls.incrementAndGet() == 63 ? io : CompletableFuture.completedFuture(null);
        }, directory);
        var renderer = new AsyncResponseRenderer(command -> { throw rejected; }, capture, () -> capture);
        var written = renderer.render(() -> capture.write(new byte[64 * 8192]));
        assertEquals(63, calls.get());
        assertFalse(written.isDone());
        assertFalse(io.isCancelled());
        io.complete(null);
        assertSame(rejected, assertThrows(ExecutionException.class,
            () -> written.get(3, TimeUnit.SECONDS)).getCause());
        assertNoTemporaryFiles();
    }

    @Test void emptyWritesStillCreateAndFlushHeadersAndReportTransportFailure() throws Exception {
        var tasks = new ArrayDeque<Runnable>();
        var io = new CompletableFuture<Void>();
        var wire = new ByteArrayOutputStream();
        var capture = new Http1ResponseOutput(OutputStream.nullOutputStream(), bytes -> {
            if (!bytes.hasRemaining()) return io;
            byte[] copy = new byte[bytes.remaining()]; bytes.get(copy); wire.write(copy);
            return CompletableFuture.completedFuture(null);
        }, directory);
        var renderer = new AsyncResponseRenderer(tasks::add, capture, () -> {
            try { capture.write(new byte[]{1, 2, 3}); }
            catch (IOException impossible) { throw new AssertionError(impossible); }
            return capture;
        });
        var written = renderer.write(ByteBuffer.allocate(0));
        assertArrayEquals(new byte[]{1, 2, 3}, wire.toByteArray());
        assertFalse(written.isDone());
        var failure = new IOException("flush failed");
        io.completeExceptionally(failure);
        runTasks(tasks);
        assertSame(failure, assertThrows(CompletionException.class, written::join).getCause());
    }

    @Test void synchronousPrefixAndLaterAsyncWritesUseTheSameEncoderState() throws Exception {
        var wire = new ByteArrayOutputStream();
        var tasks = new ArrayDeque<Runnable>();
        var capture = new Http1ResponseOutput(wire, bytes -> {
            byte[] copy = new byte[bytes.remaining()]; bytes.get(copy); wire.write(copy);
            return CompletableFuture.completedFuture(null);
        }, directory);
        var sequence = new AtomicInteger();
        var encoder = new OutputStream() {
            @Override public void write(int value) throws IOException {
                capture.write(sequence.incrementAndGet()); capture.write(value);
            }
            @Override public void flush() throws IOException { capture.flush(); }
        };
        encoder.write(11); encoder.flush();
        var renderer = new AsyncResponseRenderer(tasks::add, capture, () -> encoder);
        renderer.write(ByteBuffer.wrap(new byte[]{22})).join();
        renderer.write(ByteBuffer.wrap(new byte[]{33})).join();
        assertArrayEquals(new byte[]{1, 11, 2, 22, 3, 33}, wire.toByteArray());
    }

    @Test void failureToSealKeepsTheEncoderCauseWithoutCyclicSuppression() {
        IOException encoding = new IOException("encoder failed");
        IOException sealing = new IOException("storage failed");
        var closed = new AtomicInteger();
        ResponseOutputCapture capture = () -> new ResponseOutputCapture.Capture() {
            @Override public void finishRendering() throws IOException { throw sealing; }
            @Override public CompletableFuture<Void> writeNext() { throw new AssertionError("Unsealed output cannot drain"); }
            @Override public void close() { closed.incrementAndGet(); }
        };
        var renderer = new AsyncResponseRenderer(Runnable::run, capture, OutputStream::nullOutputStream);
        var failed = renderer.render(() -> { throw encoding; });
        assertSame(encoding, assertThrows(CompletionException.class, failed::join).getCause());
        assertArrayEquals(new Throwable[]{sealing}, encoding.getSuppressed());
        assertEquals(0, sealing.getSuppressed().length);
        assertEquals(1, closed.get());
    }

    @Test void fatalEncoderFailureDoesNotWaitForThePeerToAcceptItsPrefix() throws Exception {
        var capture = new Http1ResponseOutput(OutputStream.nullOutputStream(), bytes -> {
            throw new AssertionError("Fatal failure cannot submit another write");
        }, directory);
        var fatal = new OutOfMemoryError("injected encoder failure");
        var renderer = new AsyncResponseRenderer(Runnable::run, capture, () -> capture);
        var failed = renderer.render(() -> { capture.write(new byte[100000]); throw fatal; });
        assertSame(fatal, assertThrows(CompletionException.class, failed::join).getCause());
        assertNoTemporaryFiles();
    }

    private static void runTasks(Queue<Runnable> tasks) {
        Runnable next;
        int count = 0;
        while ((next = tasks.poll()) != null) { assertTrue(count++ < 10000); next.run(); }
    }

    private void assertNoTemporaryFiles() throws Exception {
        try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
    }
}
