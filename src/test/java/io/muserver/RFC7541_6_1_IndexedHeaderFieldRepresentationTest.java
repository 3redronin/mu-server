package io.muserver;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static io.muserver.FieldBlockEncoderTest.hexToByteArray;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("RFC 7541 6.1 Indexed Header Field Representation")
class RFC7541_6_1_IndexedHeaderFieldRepresentationTest {

    @Test
    void staticEntriesCanBeReferencedByIndex() throws Exception {
        HpackTable table = new HpackTable(4096);
        FieldBlockDecoder decoder = new FieldBlockDecoder(table, 8192, 8192 * 4);

        FieldBlock block = decoder.decodeFrom(ByteBuffer.wrap(hexToByteArray("82")));

        assertThat(block.entries(), hasSize(1));
        assertThat(block.get(":method"), equalTo("GET"));
        assertThat(table.dynamicTableSizeInBytes(), equalTo(0));
    }

    @Test
    void dynamicEntriesCanBeReferencedByIndex() throws Exception {
        HpackTable table = new HpackTable(4096);
        FieldBlockDecoder decoder = new FieldBlockDecoder(table, 8192, 8192 * 4);

        decoder.decodeFrom(ByteBuffer.wrap(hexToByteArray("410f7777772e6578616d706c652e636f6d")));
        FieldBlock block = decoder.decodeFrom(ByteBuffer.wrap(hexToByteArray("be")));

        assertThat(table.getValue(62), equalTo(fieldLine(":authority", "www.example.com")));
        assertThat(block.entries(), hasSize(1));
        assertThat(block.get(":authority"), equalTo("www.example.com"));
        assertThat(table.dynamicTableSizeInBytes(), equalTo(57));
    }

    @Test
    void indexZeroIsInvalid() {
        HpackTable table = new HpackTable(4096);
        FieldBlockDecoder decoder = new FieldBlockDecoder(table, 8192, 8192 * 4);

        var ex = assertThrows(Http2Exception.class, () -> decoder.decodeFrom(ByteBuffer.wrap(hexToByteArray("80"))));

        assertThat(ex.errorCode(), equalTo(Http2ErrorCode.COMPRESSION_ERROR));
        assertThat(ex.getMessage(), equalTo("Invalid code"));
    }

    @Test
    void missingDynamicEntriesAreRejected() {
        HpackTable table = new HpackTable(4096);
        FieldBlockDecoder decoder = new FieldBlockDecoder(table, 8192, 8192 * 4);

        var ex = assertThrows(Http2Exception.class, () -> decoder.decodeFrom(ByteBuffer.wrap(hexToByteArray("be"))));

        assertThat(ex.errorCode(), equalTo(Http2ErrorCode.COMPRESSION_ERROR));
        assertThat(ex.getMessage(), equalTo("Invalid dynamic code"));
    }

    private static FieldLine fieldLine(String name, String value) {
        return new FieldLine(HeaderString.valueOf(name, HeaderString.Type.HEADER), HeaderString.valueOf(value, HeaderString.Type.VALUE));
    }
}

