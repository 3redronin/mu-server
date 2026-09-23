package io.muserver;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.stream.Stream;

import static io.muserver.FieldBlockEncoderTest.hexToByteArray;
import static org.junit.jupiter.api.Assertions.*;

/** RFC 7541 sections 4.2 and 6.3: minimum/final sizes between field blocks. */
class HpackTableSizeTransitionTest {
    static Stream<Arguments> transitions() {
        return Stream.of(
            Arguments.of(new int[] {4096}, "", 4096),
            Arguments.of(new int[] {0}, "20", 0),
            Arguments.of(new int[] {128, 128}, "3f61", 128),
            Arguments.of(new int[] {128, 256}, "3f613fe101", 256),
            Arguments.of(new int[] {256, 128}, "3f61", 128),
            Arguments.of(new int[] {0, 4096}, "203fe11f", 4096),
            Arguments.of(new int[] {1024, 0, 256}, "203fe101", 256),
            Arguments.of(new int[] {8192, 4096}, "3fe11f", 4096)
        );
    }

    @ParameterizedTest
    @MethodSource("transitions")
    void signalsMinimumAndFinalSizeOnceBeforeTheNextField(int[] sizes, String expectedPrefix, int finalSize) throws Exception {
        HpackTable encoderTable = new HpackTable(4096);
        FieldBlockEncoder encoder = new FieldBlockEncoder(encoderTable);
        HpackTable decoderTable = new HpackTable(4096);
        FieldBlockDecoder decoder = new FieldBlockDecoder(decoderTable, 8192, 8192);
        FieldBlock response = new FieldBlock();
        response.add(":status", "204");
        assertArrayEquals(hexToByteArray("89"), encode(encoder, response));

        for (int size : sizes) encoder.changeTableSize(size);
        decoder.changeTableSize(finalSize);
        byte[] wire = encode(encoder, response);
        assertArrayEquals(hexToByteArray(expectedPrefix + "89"), wire);
        assertEquals("204", decoder.decodeFrom(ByteBuffer.wrap(wire)).get(":status"));
        assertEquals(finalSize, encoderTable.maxSize());
        assertEquals(finalSize, decoderTable.maxSize());
        assertArrayEquals(hexToByteArray("89"), encode(encoder, response), "No stale size update on the following block");
    }

    @ParameterizedTest
    @MethodSource("transitions")
    void emptyFieldBlockAlsoConsumesPendingSizeUpdates(int[] sizes, String expectedPrefix, int finalSize) throws Exception {
        FieldBlockEncoder encoder = new FieldBlockEncoder(new HpackTable(4096));
        for (int size : sizes) encoder.changeTableSize(size);
        byte[] wire = encode(encoder, new FieldBlock());
        assertArrayEquals(hexToByteArray(expectedPrefix), wire);
        HpackTable decoderTable = new HpackTable(finalSize);
        assertTrue(new FieldBlockDecoder(decoderTable, 8192, 8192).decodeFrom(ByteBuffer.wrap(wire)).isEmpty());
        assertEquals(finalSize, decoderTable.maxSize());
        assertArrayEquals(new byte[0], encode(encoder, new FieldBlock()));
    }

    private static byte[] encode(FieldBlockEncoder encoder, FieldBlock block) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        encoder.encodeTo(block, output);
        return output.toByteArray();
    }
}
