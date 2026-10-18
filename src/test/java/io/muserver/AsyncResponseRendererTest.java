package io.muserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
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
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class AsyncResponseRendererTest {
    @TempDir Path directory;

    static Stream<Arguments> sourceBoundaries() {
        return IntStream.of(16351, 16352, 16353, 16384, 16385, 32704, 32705, 32736, 32737,
                32767, 32768, 32769, 49120, 49121, 3 * 16384 + 37, 65536).boxed()
            .flatMap(size -> IntStream.range(0, 4).mapToObj(kind -> Arguments.of(kind, size)));
    }

    @ParameterizedTest @MethodSource("sourceBoundaries")
    void unencodedTurnsBorrowTheInputAndDoNotReuseItBeforeAcknowledgement(int kind, int size) throws Exception {
        var tasks = new ArrayDeque<Runnable>();
        var pending = new ArrayDeque<CompletableFuture<Void>>();
        var borrowed = new ArrayDeque<ByteBuffer>();
        var supplied = new java.util.concurrent.atomic.AtomicReference<byte[]>();
        byte[] expected = new byte[size];
        new java.util.Random(625).nextBytes(expected);
        ByteBuffer source = kind == 1 ? ByteBuffer.allocateDirect(expected.length + 9)
            : ByteBuffer.allocate(expected.length + 9);
        source.position(5).put(expected).flip().position(5);
        if (kind == 2) source = source.asReadOnlyBuffer();
        if (kind == 3) source = source.slice();
        int position = source.position();
        var capture = new Http1ResponseOutput(OutputStream.nullOutputStream(), bytes -> {
            if (bytes.hasRemaining()) assertSame(supplied.get(), bytes.array(), "Do not copy unchanged body output");
            borrowed.add(bytes);
            var io = new CompletableFuture<Void>(); pending.add(io); return io;
        }, directory);
        capture.allowSourceBorrowing(true);
        var passthrough = new OutputStream() {
            @Override public void write(int value) { fail("Expected array writes"); }
            @Override public void write(byte[] bytes, int offset, int count) throws IOException {
                supplied.set(bytes);
                capture.write(bytes, offset, count);
            }
            @Override public void flush() throws IOException { capture.flush(); }
        };
        var written = new AsyncResponseRenderer(tasks::add, capture, () -> passthrough).write(source);
        var actual = new ByteArrayOutputStream();
        int turns = 0;
        while (!written.isDone()) {
            assertTrue(++turns < 10);
            assertEquals(1, pending.size());
            assertTrue(tasks.isEmpty(), "The next render must wait for acknowledgement");
            ByteBuffer bytes = borrowed.remove();
            assertTrue(bytes.remaining() <= 16384);
            byte[] chunk = new byte[bytes.remaining()]; bytes.get(chunk); actual.write(chunk);
            pending.remove().complete(null);
            runTasks(tasks);
        }
        written.get();
        if (size <= 32768) assertEquals((size + 16383) / 16384 + 1, turns,
            "Keep one or two body writes plus the final flush, without adding a turn for headroom");
        if (size == 49120) assertEquals(4, turns, "The last 32 KiB of a larger input still needs only two body turns");
        assertArrayEquals(expected, actual.toByteArray());
        assertEquals(source.hasArray() ? position : source.limit(), source.position());
        assertNoTemporaryFiles();
    }

    @Test void oneWorkerRemainsAvailableDuringTransportBackpressure() throws Exception {
        var borrowed = new CompletableFuture<ByteBuffer>();
        var io = new CompletableFuture<Void>();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        var capture = new Http1ResponseOutput(OutputStream.nullOutputStream(), bytes -> {
            borrowed.complete(bytes); return io;
        }, directory);
        capture.allowSourceBorrowing(true);
        var renderer = new AsyncResponseRenderer(executor, capture, () -> capture);
        try {
            var written = executor.submit(() -> renderer.write(ByteBuffer.allocate(8 * 1024 * 1024)))
                .get(3, TimeUnit.SECONDS);
            assertEquals(16352, borrowed.get(3, TimeUnit.SECONDS).remaining());
            executor.submit(() -> {}).get(3, TimeUnit.SECONDS);
            assertFalse(written.isDone());
            IOException aborted = new IOException("transport aborted");
            io.completeExceptionally(aborted);
            assertSame(aborted, assertThrows(ExecutionException.class,
                () -> written.get(3, TimeUnit.SECONDS)).getCause());
        } finally { io.completeExceptionally(new IOException("test closed")); executor.shutdownNow(); }
    }

    @ParameterizedTest
    @CsvSource({"0,true", "1,true", "2,true", "3,true", "0,false"})
    void largerPlainBodyBorrowsDrainHeadersFirstAndAwaitEveryAcknowledgement(int kind, boolean large) throws Exception {
        var tasks = new ArrayDeque<Runnable>();
        var pending = new ArrayDeque<CompletableFuture<Void>>();
        var borrowed = new ArrayDeque<ByteBuffer>();
        var capture = new Http1ResponseOutput(OutputStream.nullOutputStream(), bytes -> {
            borrowed.add(bytes);
            var io = new CompletableFuture<Void>(); pending.add(io); return io;
        }, directory);
        capture.allowSourceBorrowing(true);
        capture.allowLargeSourceBorrowing(large);
        var stream = new java.io.BufferedOutputStream(capture, 8192);
        byte[] prefix = "HTTP/1.1 200 OK\r\nContent-Length: 4194341\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        stream.write(prefix);
        byte[] expected = new byte[4194341];
        new java.util.Random(727).nextBytes(expected);
        ByteBuffer source = kind == 1 ? ByteBuffer.allocateDirect(expected.length + 9)
            : ByteBuffer.allocate(expected.length + 9);
        source.position(5).put(expected).flip().position(5);
        if (kind == 2) source = source.asReadOnlyBuffer();
        if (kind == 3) source = source.slice();
        int position = source.position();
        var written = new AsyncResponseRenderer(tasks::add, capture, () -> stream).write(source);
        var actual = new ByteArrayOutputStream();
        int largeBorrows = 0;
        while (!written.isDone()) {
            assertEquals(1, pending.size());
            assertTrue(tasks.isEmpty(), "No rendering while the transport owns a borrow");
            ByteBuffer bytes = borrowed.remove();
            if (bytes.remaining() > 16384) {
                assertTrue(large && source.hasArray());
                assertSame(source.array(), bytes.array(), "Large writes must retain the original source");
                assertTrue(bytes.remaining() <= capture.maxSourceBorrowBytes());
                largeBorrows++;
            }
            byte[] chunk = new byte[bytes.remaining()]; bytes.get(chunk); actual.write(chunk);
            assertNoTemporaryFiles();
            pending.remove().complete(null);
            runTasks(tasks);
        }
        written.get();
        var wire = new ByteArrayOutputStream(); wire.write(prefix); wire.write(expected);
        assertArrayEquals(wire.toByteArray(), actual.toByteArray());
        assertEquals(large && source.hasArray() ? 4 : 0, largeBorrows);
        assertEquals(source.hasArray() ? position : source.limit(), source.position());
        assertNoTemporaryFiles();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void largeBorrowRemainsOwnedUntilTransportFailureOrRejectedContinuation(boolean reject) throws Exception {
        var io = new CompletableFuture<Void>();
        var largeBorrow = new CompletableFuture<ByteBuffer>();
        var calls = new AtomicInteger();
        byte[] source = new byte[4194341];
        new java.util.Random(728).nextBytes(source);
        var capture = new Http1ResponseOutput(OutputStream.nullOutputStream(), bytes -> {
            if (calls.incrementAndGet() == 1) return CompletableFuture.completedFuture(null);
            assertEquals(1024 * 1024, bytes.remaining());
            largeBorrow.complete(bytes); return io;
        }, directory);
        capture.allowSourceBorrowing(true);
        capture.allowLargeSourceBorrowing(true);
        var tasks = new ArrayDeque<Runnable>();
        var rejected = new RejectedExecutionException("no workers");
        var renderer = new AsyncResponseRenderer(task -> {
            if (reject) throw rejected;
            tasks.add(task);
        }, capture, () -> capture);
        var written = renderer.write(ByteBuffer.wrap(source));
        assertSame(source, largeBorrow.join().array());
        assertFalse(written.isDone());
        assertTrue(tasks.isEmpty());
        assertEquals(2, calls.get());
        IOException failure = new IOException("transport closed during large borrow");
        if (reject) io.complete(null); else io.completeExceptionally(failure);
        runTasks(tasks);
        assertSame(reject ? rejected : failure, assertThrows(CompletionException.class, written::join).getCause());
        assertEquals(2, calls.get(), "A failed response cannot submit its remaining source");
        assertNoTemporaryFiles();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void borrowedBodyWaitsForItsOwnedPrefixAndIsNotSubmittedAfterPrefixFailure(boolean reject) throws Exception {
        var io = new CompletableFuture<Void>();
        var submitted = new CompletableFuture<ByteBuffer>();
        var calls = new AtomicInteger();
        var capture = new Http1ResponseOutput(OutputStream.nullOutputStream(), bytes -> {
            assertEquals(1, calls.incrementAndGet(), "A failed prefix must not submit its borrowed body");
            submitted.complete(bytes); return io;
        }, directory);
        capture.allowSourceBorrowing(true);
        capture.allowLargeSourceBorrowing(true);
        var stream = new java.io.BufferedOutputStream(capture, 8192);
        byte[] prefix = {1, 2, 3}; stream.write(prefix);
        byte[] source = new byte[131072];
        var tasks = new ArrayDeque<Runnable>();
        var rejected = new RejectedExecutionException("no workers");
        var renderer = new AsyncResponseRenderer(task -> {
            if (reject) throw rejected;
            tasks.add(task);
        }, capture, () -> stream);
        var written = renderer.write(ByteBuffer.wrap(source));
        ByteBuffer first = submitted.join();
        assertNotSame(source, first.array());
        assertEquals(prefix.length, first.remaining());
        byte[] actual = new byte[first.remaining()]; first.get(actual); assertArrayEquals(prefix, actual);
        assertFalse(written.isDone());
        assertTrue(tasks.isEmpty());
        IOException failure = new IOException("prefix transport failed");
        if (reject) io.complete(null); else io.completeExceptionally(failure);
        runTasks(tasks);
        assertSame(reject ? rejected : failure, assertThrows(CompletionException.class, written::join).getCause());
        assertEquals(1, calls.get());
        assertNoTemporaryFiles();
    }

    @Test void encodersKeepFullInputTurnsAndOwnTheirOutputWhileAcknowledgementIsPending() throws Exception {
        var tasks = new ArrayDeque<Runnable>();
        var pending = new ArrayDeque<CompletableFuture<Void>>();
        var borrowed = new ArrayDeque<ByteBuffer>();
        var inputCounts = new java.util.ArrayList<Integer>();
        var supplied = new java.util.concurrent.atomic.AtomicReference<byte[]>();
        byte[] expected = new byte[3 * 16384 + 37];
        new java.util.Random(928).nextBytes(expected);
        var capture = new Http1ResponseOutput(OutputStream.nullOutputStream(), bytes -> {
            if (bytes.hasRemaining()) assertNotSame(supplied.get(), bytes.array(), "An encoder may reuse its input scratch");
            borrowed.add(bytes);
            var io = new CompletableFuture<Void>(); pending.add(io); return io;
        }, directory);
        var encoder = new OutputStream() {
            @Override public void write(int value) { fail("Expected array writes"); }
            @Override public void write(byte[] bytes, int offset, int count) throws IOException {
                inputCounts.add(count);
                supplied.set(bytes);
                capture.write(bytes, offset, count);
                java.util.Arrays.fill(bytes, offset, offset + count, (byte) 0);
            }
            @Override public void flush() throws IOException { capture.flush(); }
        };
        var written = new AsyncResponseRenderer(tasks::add, capture, () -> encoder)
            .write(ByteBuffer.wrap(expected).asReadOnlyBuffer());
        var actual = new ByteArrayOutputStream();
        while (!written.isDone()) {
            assertEquals(1, pending.size());
            assertTrue(tasks.isEmpty(), "The encoder cannot reuse its scratch before acknowledgement");
            ByteBuffer bytes = borrowed.remove();
            byte[] chunk = new byte[bytes.remaining()]; bytes.get(chunk); actual.write(chunk);
            pending.remove().complete(null);
            runTasks(tasks);
        }
        written.get();
        assertEquals(java.util.List.of(16384, 16384, 16384, 37), inputCounts);
        assertArrayEquals(expected, actual.toByteArray());
        assertNoTemporaryFiles();
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
            assertTrue(bytes.remaining() <= RenderedResponseBytes.DRAIN_BYTES);
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
        var written = renderer.render(() -> capture.write(new byte[64 * RenderedResponseBytes.DRAIN_BYTES]));
        assertEquals(63, calls.get());
        assertFalse(written.isDone());
        assertFalse(io.isCancelled());
        io.complete(null);
        assertSame(rejected, assertThrows(ExecutionException.class,
            () -> written.get(3, TimeUnit.SECONDS)).getCause());
        assertNoTemporaryFiles();
    }

    @Test void rejectedContinuationKeepsABorrowUntilItsTransportCompletion() throws Exception {
        var io = new CompletableFuture<Void>();
        byte[] source = new byte[16384];
        var borrowed = new CompletableFuture<ByteBuffer>();
        var capture = new Http1ResponseOutput(OutputStream.nullOutputStream(), bytes -> {
            borrowed.complete(bytes); return io;
        }, directory);
        capture.allowSourceBorrowing(true);
        var rejected = new RejectedExecutionException("no workers");
        var renderer = new AsyncResponseRenderer(task -> { throw rejected; }, capture, () -> capture);
        var written = renderer.write(ByteBuffer.wrap(source));
        assertSame(source, borrowed.join().array());
        assertFalse(written.isDone());
        io.complete(null);
        assertSame(rejected, assertThrows(ExecutionException.class, () -> written.get(3, TimeUnit.SECONDS)).getCause());
        assertFalse(io.isCancelled());
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
