package io.muserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.util.List;

import static io.muserver.FieldBlockEncoderTest.hexToByteArray;
import static org.junit.jupiter.api.Assertions.*;

/** Deterministic boundary cases for RFC 7541 sections 2–6. */
class HpackConformanceTest {
    private final HpackTable table = new HpackTable(4096);
    private final FieldBlockDecoder decoder = new FieldBlockDecoder(table, 8192, 32768);

    private FieldBlock decode(String hex) throws Exception {
        return decoder.decodeFrom(ByteBuffer.wrap(hexToByteArray(hex)));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
    void integerPrefixBoundariesConsumeOnlyTheirOwnBytes(int bits) throws Exception {
        // RFC 7541 section 5.1: prefix maximum requires a continuation, even for zero remainder.
        int maximum = (1 << bits) - 1;
        ByteBuffer following = ByteBuffer.wrap(new byte[] {0, 42});
        assertEquals(maximum - 1, FieldBlockDecoder.readHpackInt(bits, (byte) (maximum - 1), following));
        assertEquals(0, following.position());
        assertEquals(maximum, FieldBlockDecoder.readHpackInt(bits, (byte) maximum, following));
        assertEquals(1, following.position());
        assertEquals(42, following.get());
        assertEquals(maximum + 128, FieldBlockDecoder.readHpackInt(bits, (byte) maximum,
            ByteBuffer.wrap(new byte[] {(byte) 128, 1})));
    }

    @ParameterizedTest
    @ValueSource(strings = {"80", "be", "ff00", "0f2f00", "7e00"})
    void unavailableIndexesAreConnectionCompressionErrors(String hex) {
        // Sections 2.3.3 and 6.1: zero and indexes beyond the current table are invalid.
        Http2Exception error = assertThrows(Http2Exception.class, () -> decode(hex));
        assertEquals(Http2ErrorCode.COMPRESSION_ERROR, error.errorCode());
        assertEquals(Http2Level.CONNECTION, error.errorType());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0001780179", "1001780179", "4001780179"})
    void literalIndexingModesHaveDistinctTableEffects(String hex) throws Exception {
        // Sections 6.2.1–6.2.3: identical fields, different indexing instructions.
        FieldBlock fields = decode(hex);
        assertEquals("y", fields.get("x"));
        assertEquals(1, fields.size());
        assertEquals(hex.startsWith("10"), fields.lineIterator().iterator().next().neverIndexed());
        if (hex.startsWith("40")) {
            assertEquals(34, table.dynamicTableSizeInBytes());
            assertEquals("y", decode("be").get("x"));
        } else {
            assertEquals(0, table.dynamicTableSizeInBytes());
        }
    }

    @Test
    void duplicateEntriesRetainOrderAndOccupySeparateTableSlots() throws Exception {
        // Sections 2.3.2 and 3.1: duplicates are allowed and field order is preserved.
        assertEquals(List.of("a", "b", "a"), decode("4001780161 4001780162 4001780161").getAll("x"));
        assertEquals(102, table.dynamicTableSizeInBytes());
        assertEquals(List.of("a", "b", "a"), decode("be bf c0").getAll("x"));
    }

    @Test
    void zeroThenRestoreClearsOldEntriesAndAllowsNewEntries() throws Exception {
        // Sections 4.2 and 6.3: both updates precede the next field.
        decode("4001780161");
        decode("20 3fe11f 4001780162");
        assertEquals(4096, table.maxSize());
        assertEquals(34, table.dynamicTableSizeInBytes());
        assertEquals("b", decode("be").get("x"));
        assertThrows(Http2Exception.class, () -> decode("bf"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"8220", "000178017920", "100178017920", "400178017920", "3fe21f"})
    void sizeUpdateErrors(String hex) {
        // Sections 4.2 and 6.3: updates must precede fields and respect the advertised maximum.
        Http2Exception error = assertThrows(Http2Exception.class, () -> decode(hex));
        assertEquals(Http2ErrorCode.COMPRESSION_ERROR, error.errorCode());
        assertEquals(Http2Level.CONNECTION, error.errorType());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 33, 34, 35, 68})
    void entrySizeBoundaryUsesDecodedOctetsAnd32ByteOverhead(int capacity) throws Exception {
        table.changeMaxSize(capacity);
        assertEquals("a", decode("4001780161").get("x"));
        assertEquals(capacity < 34 ? 0 : 34, table.dynamicTableSizeInBytes());
        decode("4001780162");
        assertEquals(capacity < 34 ? 0 : capacity < 68 ? 34 : 68, table.dynamicTableSizeInBytes());
        if (capacity >= 34) assertEquals("b", table.getValue(62).value().toString());
        if (capacity >= 68) assertEquals("a", table.getValue(63).value().toString());
    }

    @Test
    void oversizedEntryClearsTableWithoutRejectingField() throws Exception {
        table.changeMaxSize(34);
        decode("4001780161");
        assertEquals("ab", decode("400178026162").get("x"));
        assertEquals(0, table.dynamicTableSizeInBytes());
    }

    @Test
    void indexedNameSurvivesEvictionOfItsSourceEntry() throws Exception {
        // Section 4.4 explicitly permits this case.
        table.changeMaxSize(34);
        decode("4001780161");
        assertEquals("b", decode("7e0162").get("x"));
        assertEquals(34, table.dynamicTableSizeInBytes());
        assertEquals("b", decode("be").get("x"));
    }

    @Test
    void emptyValuesAndEmptyBlocksArePreserved() throws Exception {
        assertEquals("", decode("40017800").get("x"));
        assertEquals(33, table.dynamicTableSizeInBytes());
        assertTrue(decode("").isEmpty());
        assertEquals("", decode("be").get("x"));
    }

    @Test
    void rejectedHeaderListStillUpdatesCompressionContext() throws Exception {
        // RFC 9113 section 4.3: decode the entire field block to keep the connection context in sync.
        FieldBlockDecoder limited = new FieldBlockDecoder(table, 8192, 34);
        HttpException error = assertThrows(HttpException.class,
            () -> limited.decodeFrom(ByteBuffer.wrap(hexToByteArray("4001780161 4001790162"))));
        assertEquals(HttpStatus.REQUEST_HEADER_FIELDS_TOO_LARGE_431, error.status());
        assertEquals("b", limited.decodeFrom(ByteBuffer.wrap(hexToByteArray("be"))).get("y"));
        assertEquals(68, table.dynamicTableSizeInBytes());
    }

    @ParameterizedTest
    @ValueSource(strings = {"heap", "direct", "readonly", "slice"})
    void decodingRespectsBufferPositionAndLimit(String kind) throws Exception {
        byte[] bytes = hexToByteArray("0082868400");
        ByteBuffer input = kind.equals("direct") ? ByteBuffer.allocateDirect(bytes.length) : ByteBuffer.allocate(bytes.length);
        input.put(bytes).flip();
        input.position(1).limit(4);
        if (kind.equals("readonly")) input = input.asReadOnlyBuffer();
        if (kind.equals("slice")) input = input.slice();
        FieldBlock fields = decoder.decodeFrom(input);
        assertEquals("GET", fields.get(":method"));
        assertEquals("http", fields.get(":scheme"));
        assertEquals("/", fields.get(":path"));
        assertEquals(input.limit(), input.position());
    }
}
