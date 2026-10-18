package io.muserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class Http1ResponseOutputTest {
    @TempDir Path directory;

    @ParameterizedTest @ValueSource(ints = {0, 1, 8191, 16351, 32768})
    void ownsThePrefixButBorrowsTheExactBodyWithoutSpilling(int prefixLength) throws Exception {
        byte[] prefix = randomBytes(prefixLength), body = randomBytes(1048576 + 9);
        byte[] expectedPrefix = prefix.clone();
        List<ByteBuffer> writes = new ArrayList<>();
        var output = output(directory, writes);
        try (var capture = output.begin()) {
            capture.borrowSourceIfUnchanged(body, 5, 1048576);
            output.write(prefix);
            Arrays.fill(prefix, (byte) 99); // A stream may immediately reuse its own scratch.
            output.write(body, 5, 1048576);
            output.write(body, 0, 0); // Empty writes must not materialize an existing borrow.
            capture.finishRendering();
            var actualPrefix = new ByteArrayOutputStream();
            int consumed = 0;
            while (consumed < prefixLength) {
                capture.writeNext().join();
                ByteBuffer part = writes.remove(0);
                assertNotSame(body, part.array());
                assertNotSame(prefix, part.array());
                assertTrue(part.remaining() <= 16384);
                byte[] copy = new byte[part.remaining()]; part.get(copy); actualPrefix.write(copy);
                consumed += copy.length;
            }
            assertArrayEquals(expectedPrefix, actualPrefix.toByteArray());
            capture.writeNext().join();
            ByteBuffer tail = writes.remove(0);
            assertSame(body, tail.array(), "The body must not be copied together with the prefix");
            assertEquals(5, tail.arrayOffset() + tail.position());
            assertEquals(1048576, tail.remaining());
            assertNull(capture.writeNext());
            assertThrows(IOException.class, () -> capture.borrowSourceIfUnchanged(body, 0, 1));
            assertNoTemporaryFiles();
        }
        assertNoTemporaryFiles();
    }

    @ParameterizedTest @ValueSource(ints = {16, 65408, 262144, 1048576})
    void outputAfterABorrowMaterializesItBeforeTheSourceCanBeReused(int length) throws Exception {
        byte[] body = randomBytes(length);
        var expected = new ByteArrayOutputStream(); expected.write(41); expected.write(body); expected.write(42);
        List<ByteBuffer> writes = new ArrayList<>();
        var output = output(directory, writes);
        try (var capture = output.begin()) {
            capture.borrowSourceIfUnchanged(body, 0, body.length);
            output.write(41); output.write(body); output.write(42);
            Arrays.fill(body, (byte) 0);
            capture.finishRendering();
            assertArrayEquals(expected.toByteArray(), drain(capture, writes));
        }
        assertNoTemporaryFiles();
    }

    @Test void failedTailMaterializationStillDrainsThePreviouslyProducedPrefixAndBorrow() throws Exception {
        Path unusable = Files.createFile(directory.resolve("not-a-directory"));
        byte[] body = randomBytes(1048576);
        List<ByteBuffer> writes = new ArrayList<>();
        var output = output(unusable, writes);
        try (var capture = output.begin()) {
            capture.borrowSourceIfUnchanged(body, 0, body.length);
            output.write(41); output.write(body);
            IOException failure = assertThrows(IOException.class, () -> output.write(42));
            assertSame(failure, assertThrows(IOException.class, () -> output.write(43)));
            capture.finishRendering();
            var expected = new ByteArrayOutputStream(); expected.write(41); expected.write(body);
            assertArrayEquals(expected.toByteArray(), drain(capture, writes));
        }
        Files.delete(unusable);
        assertNoTemporaryFiles();
    }

    @Test void aSpilledPrefixKeepsTheOwnedFallback() throws Exception {
        byte[] prefix = randomBytes(32769), body = randomBytes(16352);
        var expected = new ByteArrayOutputStream(); expected.write(prefix); expected.write(body);
        List<ByteBuffer> writes = new ArrayList<>();
        var output = output(directory, writes);
        try (var capture = output.begin()) {
            capture.borrowSourceIfUnchanged(body, 0, body.length);
            output.write(prefix); output.write(body);
            Arrays.fill(body, (byte) 0);
            capture.finishRendering();
            assertArrayEquals(expected.toByteArray(), drain(capture, writes));
        }
        assertNoTemporaryFiles();
    }

    @Test void aFailedPrefixCannotBeFollowedByAnOtherwiseEligibleBorrow() throws Exception {
        Path unusable = Files.createFile(directory.resolve("not-a-directory"));
        byte[] body = randomBytes(16352);
        List<ByteBuffer> writes = new ArrayList<>();
        var output = output(unusable, writes);
        try (var capture = output.begin()) {
            capture.borrowSourceIfUnchanged(body, 0, body.length);
            output.write(41);
            IOException failure = assertThrows(IOException.class, () -> output.write(new byte[32768]));
            assertSame(failure, assertThrows(IOException.class, () -> output.write(body)));
            capture.finishRendering();
            assertArrayEquals(new byte[]{41}, drain(capture, writes));
        }
        Files.delete(unusable);
        assertNoTemporaryFiles();
    }

    @Test void borrowOffersAreBoundedAndAnUnusedOfferEmitsNothing() throws Exception {
        List<ByteBuffer> writes = new ArrayList<>();
        var output = output(directory, writes);
        byte[] body = new byte[1048577];
        try (var capture = output.begin()) {
            assertThrows(IllegalArgumentException.class, () -> capture.borrowSourceIfUnchanged(body, 0, body.length));
            assertThrows(IndexOutOfBoundsException.class, () -> capture.borrowSourceIfUnchanged(body, -1, 1));
            capture.borrowSourceIfUnchanged(body, 1, 1048576);
            capture.finishRendering();
            assertNull(capture.writeNext());
            assertTrue(writes.isEmpty());
        }
        assertNoTemporaryFiles();
    }

    private Http1ResponseOutput output(Path path, List<ByteBuffer> writes) {
        var output = new Http1ResponseOutput(OutputStream.nullOutputStream(), bytes -> {
            writes.add(bytes); return CompletableFuture.completedFuture(null);
        }, path);
        output.allowSourceBorrowing(true);
        output.allowLargeSourceBorrowing(true);
        return output;
    }

    private static byte[] drain(ResponseOutputCapture.Capture capture, List<ByteBuffer> writes) throws Exception {
        var actual = new ByteArrayOutputStream();
        CompletableFuture<?> next;
        while ((next = capture.writeNext()) != null) {
            next.join();
            ByteBuffer part = writes.remove(0);
            byte[] copy = new byte[part.remaining()]; part.get(copy); actual.write(copy);
        }
        return actual.toByteArray();
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length]; new Random(718).nextBytes(bytes); return bytes;
    }

    private void assertNoTemporaryFiles() throws IOException {
        try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
    }
}
