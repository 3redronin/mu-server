package io.muserver;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

import static io.muserver.FieldBlockEncoderTest.bytesToHex;
import static io.muserver.FieldBlockEncoderTest.hexToByteArray;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

@DisplayName("RFC 7541 6.2.3 Literal Header Field Never Indexed")
class RFC7541_6_2_3_LiteralHeaderFieldNeverIndexedTest {

    @Test
    void neverIndexedHeadersDoNotEnterTheDynamicTable() throws Exception {
        HpackTable table = new HpackTable(4096);
        FieldBlockDecoder decoder = new FieldBlockDecoder(table, 8192, 8192 * 4);

        FieldBlock block = decoder.decodeFrom(ByteBuffer.wrap(hexToByteArray("100870617373776f726406736563726574")));
        FieldLine line = fieldLine("password", "secret");

        assertThat(block.entries(), hasSize(1));
        assertThat(block.get("password"), equalTo("secret"));
        assertThat(table.dynamicTableSizeInBytes(), equalTo(0));
        assertThat(table.isNeverIndex(line), equalTo(true));
    }

    @Test
    void neverIndexedHeadersRemainNeverIndexedWhenReencoded() throws Exception {
        HpackTable table = new HpackTable(4096);
        FieldBlockDecoder decoder = new FieldBlockDecoder(table, 8192, 8192 * 4);
        FieldBlockEncoder encoder = new FieldBlockEncoder(table);

        FieldBlock block = decoder.decodeFrom(ByteBuffer.wrap(hexToByteArray("100870617373776f726406736563726574")));

        try (var out = new ByteArrayOutputStream()) {
            encoder.encodeTo(block, out);
            assertThat(bytesToHex(out.toByteArray()), equalTo("100870617373776f726406736563726574"));
        }
    }

    private static FieldLine fieldLine(String name, String value) {
        return new FieldLine(HeaderString.valueOf(name, HeaderString.Type.HEADER), HeaderString.valueOf(value, HeaderString.Type.VALUE));
    }
}

