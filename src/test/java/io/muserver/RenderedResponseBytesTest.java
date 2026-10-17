package io.muserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class RenderedResponseBytesTest {
    @TempDir Path directory;

    @Test void aSingleUnchangedWriteBorrowsOnlyTheOfferedRange() throws Exception {
        byte[] source = new byte[RenderedResponseBytes.DRAIN_BYTES + 17];
        new Random(892).nextBytes(source);
        try (var capture = new RenderedResponseBytes(directory)) {
            capture.borrowSourceIfUnchanged(source, 7, RenderedResponseBytes.DRAIN_BYTES);
            capture.write(source, 7, RenderedResponseBytes.DRAIN_BYTES);
            capture.seal();
            ByteBuffer next = capture.next();
            assertSame(source, next.array());
            assertEquals(7, next.arrayOffset() + next.position());
            assertEquals(RenderedResponseBytes.DRAIN_BYTES, next.remaining());
            assertNull(capture.next());
            assertFalse(capture.spilled());
            capture.close();
            assertThrows(IOException.class, capture::next);
            assertThrows(IOException.class, () -> capture.borrowSourceIfUnchanged(source, 7, 1));
        }
        assertNoTemporaryFiles();
    }

    @ParameterizedTest @ValueSource(ints = {0, 1, 2})
    void aDifferentArrayOffsetOrLengthKeepsOwnedStorage(int difference) throws Exception {
        byte[] offered = new byte[32];
        new Random(729).nextBytes(offered);
        byte[] actual = difference == 0 ? offered.clone() : offered;
        int offset = difference == 1 ? 4 : 3;
        int count = difference == 2 ? 15 : 16;
        byte[] expected = Arrays.copyOfRange(actual, offset, offset + count);
        try (var capture = new RenderedResponseBytes(directory)) {
            capture.borrowSourceIfUnchanged(offered, 3, 16);
            capture.write(actual, offset, count);
            capture.seal();
            Arrays.fill(actual, (byte) 0);
            assertArrayEquals(expected, drain(capture));
        }
    }

    @ParameterizedTest @CsvSource({
        "1, 0", "1, 1", "1, 40000",
        "256, 0", "256, 1", "256, 40000",
        "257, 0", "257, 1", "257, 40000",
        "16384, 0", "16384, 1", "16384, 40000"
    })
    void mixedOutputMaterializesTheBorrowBeforeAppendingOrSpilling(int bodyLength, int suffixLength) throws Exception {
        byte[] body = new byte[bodyLength];
        new Random(47).nextBytes(body);
        var expected = new ByteArrayOutputStream();
        try (var capture = new RenderedResponseBytes(directory)) {
            if (suffixLength == 0) { capture.write(77); expected.write(77); }
            capture.borrowSourceIfUnchanged(body, 0, body.length);
            capture.write(body); expected.write(body);
            if (suffixLength > 0) {
                byte[] suffix = new byte[suffixLength];
                Arrays.fill(suffix, (byte) 88);
                capture.write(suffix); expected.write(suffix);
            }
            capture.seal();
            Arrays.fill(body, (byte) 0); // Fallback owns its copy, including the formerly borrowed prefix.
            assertArrayEquals(expected.toByteArray(), drain(capture));
        }
        assertNoTemporaryFiles();
    }

    @Test void failedSpillAfterABorrowPreservesTheMaterializedPrefix() throws Exception {
        Path unusable = Files.createFile(directory.resolve("not-a-directory"));
        byte[] source = new byte[16384];
        new Random(987).nextBytes(source);
        byte[] expected = source.clone();
        try (var capture = new RenderedResponseBytes(unusable)) {
            capture.borrowSourceIfUnchanged(source, 0, source.length);
            capture.write(source);
            IOException failure = assertThrows(IOException.class, () -> capture.write(new byte[40000]));
            assertSame(failure, assertThrows(IOException.class, () -> capture.write(1)));
            Arrays.fill(source, (byte) 0);
            capture.seal();
            assertArrayEquals(expected, drain(capture));
        }
        Files.delete(unusable);
        assertNoTemporaryFiles();
    }

    @Test void borrowOffersCannotExceedTheRenderTurnOrArriveAfterSealing() throws Exception {
        try (var capture = new RenderedResponseBytes(directory)) {
            byte[] source = new byte[RenderedResponseBytes.DRAIN_BYTES + 1];
            assertThrows(IllegalArgumentException.class, () -> capture.borrowSourceIfUnchanged(source, 0, source.length));
            capture.borrowSourceIfUnchanged(source, 0, 17);
            capture.seal(); // An offer without a write produces no output.
            assertNull(capture.next());
            assertThrows(IOException.class, () -> capture.borrowSourceIfUnchanged(source, 0, 17));
        }
    }

    @ParameterizedTest @ValueSource(ints = {0, 1, 8192, 16383, 16384, 16385, 32768, 32769, 1048576})
    void ownsAnEncoderBurstAndDrainsBoundedChunks(int size) throws Exception {
        byte[] expected = new byte[size];
        new Random(1234).nextBytes(expected);
        try (var capture = new RenderedResponseBytes(directory)) {
            byte[] encoderBuffer = expected.clone();
            capture.write(encoderBuffer);
            Arrays.fill(encoderBuffer, (byte) 0);
            assertEquals(size, capture.length());
            assertEquals(size > RenderedResponseBytes.MEMORY_LIMIT, capture.spilled());
            capture.seal();
            assertArrayEquals(expected, drain(capture));
            assertNull(capture.next());
        }
        assertNoTemporaryFiles();
    }

    @Test void tinyWritesAndArraySlicesKeepTheirOrderAcrossSpill() throws Exception {
        var expected = new ByteArrayOutputStream();
        try (var capture = new RenderedResponseBytes(directory)) {
            for (int i = 0; i < 20000; i++) {
                capture.write(i); expected.write(i);
                byte[] reused = {(byte) 77, (byte) i, (byte) (i + 1), (byte) 88};
                capture.write(reused, 1, 2); expected.write(reused, 1, 2);
                Arrays.fill(reused, (byte) 0);
            }
            capture.seal();
            assertArrayEquals(expected.toByteArray(), drain(capture));
        }
        assertNoTemporaryFiles();
    }

    @ParameterizedTest @ValueSource(ints = {16385, 20000, 32767})
    void unevenEncoderWritesKeepTheBackingArrayWithinTheMemoryLimit(int firstWrite) throws Exception {
        byte[] expected = new byte[RenderedResponseBytes.MEMORY_LIMIT];
        new Random(7654).nextBytes(expected);
        try (var capture = new RenderedResponseBytes(directory)) {
            capture.write(expected, 0, firstWrite);
            capture.write(expected, firstWrite, expected.length - firstWrite);
            assertFalse(capture.spilled());
            capture.seal();
            assertArrayEquals(expected, drain(capture));
        }
        assertNoTemporaryFiles();
    }

    @Test void abortedCaptureDeletesItsSpillWithoutRequiringARead() throws Exception {
        var capture = new RenderedResponseBytes(directory);
        capture.write(new byte[100000]);
        assertTrue(capture.spilled());
        capture.close();
        capture.close();
        assertThrows(IOException.class, capture::seal);
        assertThrows(IOException.class, capture::next);
        assertThrows(IOException.class, () -> capture.write(1));
        assertNoTemporaryFiles();
    }

    @Test void failedSpillStillAllowsAlreadyProducedBytesToDrain() throws Exception {
        Path unusable = Files.createFile(directory.resolve("file-instead-of-directory"));
        try (var capture = new RenderedResponseBytes(unusable)) {
            capture.write(new byte[]{1, 2, 3});
            IOException failure = assertThrows(IOException.class, () -> capture.write(new byte[100000]));
            assertSame(failure, assertThrows(IOException.class, () -> capture.write(4)));
            capture.seal();
            assertArrayEquals(new byte[]{1, 2, 3}, drain(capture));
        }
        Files.delete(unusable);
        assertNoTemporaryFiles();
    }

    @Test void sealingPreventsReuseWhileOutputIsBorrowed() throws Exception {
        try (var capture = new RenderedResponseBytes(directory)) {
            assertThrows(IOException.class, capture::next);
            capture.write(new byte[]{1, 2, 3});
            capture.seal();
            capture.seal();
            ByteBuffer borrowed = capture.next();
            assertThrows(IOException.class, () -> capture.write(new byte[]{9, 9, 9}));
            assertArrayEquals(new byte[]{1, 2, 3}, new byte[]{borrowed.get(), borrowed.get(), borrowed.get()});
            assertNull(capture.next());
        }
    }

    private static byte[] drain(RenderedResponseBytes capture) throws Exception {
        var result = new ByteArrayOutputStream();
        ByteBuffer next;
        while ((next = capture.next()) != null) {
            assertTrue(next.remaining() > 0 && next.remaining() <= RenderedResponseBytes.DRAIN_BYTES);
            assertTrue(next.array().length <= RenderedResponseBytes.MEMORY_LIMIT,
                "Capture backing storage exceeded its memory bound: " + next.array().length);
            byte[] bytes = new byte[next.remaining()];
            next.get(bytes);
            result.write(bytes);
        }
        assertFalse(capture.hasRemaining());
        return result.toByteArray();
    }

    private void assertNoTemporaryFiles() throws Exception {
        try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
    }
}
