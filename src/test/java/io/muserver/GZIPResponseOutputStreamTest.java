package io.muserver;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Random;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class GZIPResponseOutputStreamTest {
    @ParameterizedTest
    @ValueSource(ints = {1, 7, 512, 8192})
    void streamingOutputMatchesJdkIncludingFlushAndTrailer(int bufferSize) throws Exception {
        byte[] body = new byte[65536];
        new Random(31).nextBytes(body);
        var expected = new ByteArrayOutputStream();
        var actual = new ByteArrayOutputStream();
        try (var jdk = new GZIPOutputStream(expected, bufferSize, true);
             var response = new GZIPResponseOutputStream(actual, bufferSize)) {
            writeChunks(jdk, body);
            writeChunks(response, body);
            jdk.finish();
            response.finish();
            // Finishing twice must not emit a second trailer.
            response.finish();
            assertThrows(IOException.class, () -> response.write(1));
        }
        assertArrayEquals(expected.toByteArray(), actual.toByteArray());
        try (var decoded = new GZIPInputStream(new ByteArrayInputStream(actual.toByteArray()))) {
            assertArrayEquals(body, decoded.readAllBytes());
        }
    }

    private static void writeChunks(OutputStream stream, byte[] body) throws IOException {
        stream.write(body[0]);
        stream.write(body, 1, 0);
        stream.write(body, 1, 123);
        stream.flush();
        for (int offset = 124; offset < body.length; offset += 8192) {
            stream.write(body, offset, Math.min(8192, body.length - offset));
        }
        stream.flush();
    }

    @Test
    void flushMakesBytesReadableBeforeClose() throws Exception {
        byte[] body = new byte[20000];
        new Random(41).nextBytes(body);
        var bytes = new ByteArrayOutputStream();
        try (var response = new GZIPResponseOutputStream(bytes, 512)) {
            response.write(body);
            response.flush();
            try (var decoded = new GZIPInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
                assertArrayEquals(body, decoded.readNBytes(body.length));
            }
        }
    }

    @Test
    void emptyResponseAndRepeatedCloseMatchJdk() throws Exception {
        var expected = new ByteArrayOutputStream();
        var actual = new ByteArrayOutputStream();
        var response = new GZIPResponseOutputStream(actual, 512);
        response.close();
        response.close();
        try (var jdk = new GZIPOutputStream(expected, 512, true)) {
            jdk.finish();
        }
        assertArrayEquals(expected.toByteArray(), actual.toByteArray());
    }

    @Test
    void invalidRangesDoNotCorruptTheStream() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var response = new GZIPResponseOutputStream(bytes, 512)) {
            assertThrows(IndexOutOfBoundsException.class, () -> response.write(new byte[2], 1, 2));
            assertThrows(IndexOutOfBoundsException.class, () -> response.write(new byte[2], 0, -1));
            response.write(new byte[]{1, 2, 3});
        }
        try (var decoded = new GZIPInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            assertArrayEquals(new byte[]{1, 2, 3}, decoded.readAllBytes());
        }
    }
}
