package io.muserver;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static io.muserver.FieldBlockEncoderTest.hexToByteArray;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

@DisplayName("RFC 7541 6.2.1 Literal Header Field with Incremental Indexing")
class RFC7541_6_2_1_LiteralHeaderFieldWithIncrementalIndexingTest {

    @Test
    void newNamesCanBeAddedToTheDynamicTable() throws Exception {
        HpackTable table = new HpackTable(4096);
        FieldBlockDecoder decoder = new FieldBlockDecoder(table, 8192, 8192 * 4);

        FieldBlock block = decoder.decodeFrom(ByteBuffer.wrap(hexToByteArray("400a637573746f6d2d6b65790d637573746f6d2d686561646572")));

        assertThat(block.entries(), hasSize(1));
        assertThat(block.get("custom-key"), equalTo("custom-header"));
        assertThat(table.getValue(62), equalTo(fieldLine("custom-key", "custom-header")));
        assertThat(table.dynamicTableSizeInBytes(), equalTo(55));
    }

    @Test
    void indexedNamesCanBeAddedToTheDynamicTable() throws Exception {
        HpackTable table = new HpackTable(4096);
        FieldBlockDecoder decoder = new FieldBlockDecoder(table, 8192, 8192 * 4);

        FieldBlock block = decoder.decodeFrom(ByteBuffer.wrap(hexToByteArray("58086e6f2d6361636865")));

        assertThat(block.entries(), hasSize(1));
        assertThat(block.get("cache-control"), equalTo("no-cache"));
        assertThat(table.getValue(62), equalTo(fieldLine("cache-control", "no-cache")));
        assertThat(table.dynamicTableSizeInBytes(), equalTo(53));
    }

    private static FieldLine fieldLine(String name, String value) {
        return new FieldLine(HeaderString.valueOf(name, HeaderString.Type.HEADER), HeaderString.valueOf(value, HeaderString.Type.VALUE));
    }
}

