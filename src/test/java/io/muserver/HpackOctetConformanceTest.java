package io.muserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static io.muserver.FieldConformanceFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class HpackOctetConformanceTest {
    static Stream<Arguments> fields() {
        var cases = new ArrayList<Arguments>();
        for (boolean huffman : List.of(false, true)) {
            for (String name : List.of("Content-Type", "CONTENT-LENGTH", "X-Custom", "", "x:y", "x y",
                "x\u0000y", "x\u00ff", ":status", ":unknown")) {
                cases.add(Arguments.of("name-" + cases.size(), name, "MiXeD", huffman));
            }
            for (int c = 0; c <= 255; c++) {
                cases.add(Arguments.of("value-" + c + "-" + huffman, "x-octet", "" + (char) c, huffman));
            }
            for (String value : List.of("", " value ", "\tvalue\t", "a  \t b", "z".repeat(127),
                "z".repeat(128), "z".repeat(256))) {
                cases.add(Arguments.of("value-boundary-" + cases.size(), "x-value", value, huffman));
            }
        }
        return cases.stream();
    }

    @Test
    void compressionValidFieldsPreserveOctetsAndIndexEvenWhenHttpInvalid() {
        for (Arguments args : fields().toArray(Arguments[]::new)) {
            Object[] values = args.get();
            String description = (String) values[0];
            String name = (String) values[1];
            String value = (String) values[2];
            boolean huffman = (boolean) values[3];
            assertAll(description, () -> assertCompressionField(description, name, value, huffman));
        }
    }

    private static void assertCompressionField(String description, String name, String value,
                                               boolean huffman) throws Exception {
        var table = new HpackTable(4096);
        var decoder = new FieldBlockDecoder(table, 8192, 32768);
        byte[] block = concat(literal(name, value, true, huffman),
            literal("x-sentinel", "After-Invalid", true, huffman));
        FieldBlock decoded = decoder.decodeFrom(ByteBuffer.wrap(block));
        var lines = new ArrayList<FieldLine>();
        decoded.lineIterator().forEach(lines::add);
        assertEquals(2, lines.size());
        // Keep byte, string, character and table assertions independent: one must not mask another.
        var repeated = decoder.decodeFrom(ByteBuffer.wrap(concat(indexed(63), indexed(62))));
        var repeatedLines = new ArrayList<FieldLine>();
        repeated.lineIterator().forEach(repeatedLines::add);
        assertAll(description,
            () -> exact(lines.get(0).name(), name),
            () -> exact(lines.get(0).value(), value),
            () -> exact(repeatedLines.get(0).name(), name),
            () -> exact(repeatedLines.get(0).value(), value),
            () -> assertEquals("After-Invalid", repeated.get("x-sentinel")),
            () -> assertEquals(64 + octets(name).length + octets(value).length
                + "x-sentinel".length() + "After-Invalid".length(), table.dynamicTableSizeInBytes()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void responseAndTrailerShapedBlocksHaveOctetNotMessageSemantics(boolean huffman) throws Exception {
        var decoder = new FieldBlockDecoder(new HpackTable(4096), 8192, 32768);
        var response = decoder.decodeFrom(ByteBuffer.wrap(concat(indexed(8),
            literal("Content-Type", " \u0085\u00a0MiXeD\u00ff\t", true, huffman))));
        assertEquals("200", response.get(":status"));
        var trailer = decoder.decodeFrom(ByteBuffer.wrap(indexed(62)));
        var line = trailer.lineIterator().iterator().next();
        assertAll(() -> exact(line.name(), "Content-Type"),
            () -> exact(line.value(), " \u0085\u00a0MiXeD\u00ff\t"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void invalidHttpEntriesStillConsumeCapacityAndEvictEarlierEntries(boolean huffman) throws Exception {
        var table = new HpackTable(64);
        var decoder = new FieldBlockDecoder(table, 8192, 32768);
        decoder.decodeFrom(ByteBuffer.wrap(literal("x-old", "old", true, false)));
        decoder.decodeFrom(ByteBuffer.wrap(literal("x-bad", "\u0000", true, huffman)));
        assertEquals(38, table.dynamicTableSizeInBytes());
        var invalidEntry = table.getValue(62);
        assertAll(() -> exact(invalidEntry.name(), "x-bad"),
            () -> exact(invalidEntry.value(), "\u0000"));
        var evicted = assertThrows(Http2Exception.class, () -> table.getValue(63));
        assertEquals(Http2ErrorCode.COMPRESSION_ERROR, evicted.errorCode());

        decoder.decodeFrom(ByteBuffer.wrap(literal("x-sentinel", "exact-value", true, huffman)));
        assertEquals(53, table.dynamicTableSizeInBytes());
        assertEquals("exact-value", decoder.decodeFrom(ByteBuffer.wrap(indexed(62))).get("x-sentinel"));
        var unavailable = assertThrows(Http2Exception.class,
            () -> decoder.decodeFrom(ByteBuffer.wrap(indexed(63))));
        assertEquals(Http2ErrorCode.COMPRESSION_ERROR, unavailable.errorCode());
    }

    private static void exact(HeaderString actual, String expected) {
        assertArrayEquals(octets(expected), actual.bytes);
        assertEquals(expected.length(), actual.length());
        assertEquals(expected, actual.toString());
        assertEquals(expected, new String(actual.bytes, StandardCharsets.ISO_8859_1));
        for (int i = 0; i < expected.length(); i++) assertEquals(expected.charAt(i), actual.charAt(i));
    }
}
