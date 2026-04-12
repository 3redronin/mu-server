package io.muserver;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static io.muserver.FieldBlockEncoderTest.hexToByteArray;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

@DisplayName("RFC 7541 6.2.2 Literal Header Field without Indexing")
class RFC7541_6_2_2_LiteralHeaderFieldWithoutIndexingTest {

    @Test
    void indexedNamesCanBeSentWithoutIndexing() throws Exception {
        HpackTable table = new HpackTable(4096);
        FieldBlockDecoder decoder = new FieldBlockDecoder(table, 8192, 8192 * 4);

        FieldBlock block = decoder.decodeFrom(ByteBuffer.wrap(hexToByteArray("040c2f73616d706c652f70617468")));

        assertThat(block.entries(), hasSize(1));
        assertThat(block.get(":path"), equalTo("/sample/path"));
        assertThat(table.dynamicTableSizeInBytes(), equalTo(0));
    }

    @Test
    void newNamesCanBeSentWithoutIndexing() throws Exception {
        HpackTable table = new HpackTable(4096);
        FieldBlockDecoder decoder = new FieldBlockDecoder(table, 8192, 8192 * 4);

        FieldBlock block = decoder.decodeFrom(ByteBuffer.wrap(hexToByteArray("0003666f6f03626172")));

        assertThat(block.entries(), hasSize(1));
        assertThat(block.get("foo"), equalTo("bar"));
        assertThat(table.dynamicTableSizeInBytes(), equalTo(0));
    }
}

