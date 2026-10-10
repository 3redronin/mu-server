package io.muserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
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

    @ParameterizedTest @ValueSource(ints = {0, 1, 8192, 32768, 32769, 1048576})
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
