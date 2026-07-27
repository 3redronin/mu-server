package io.muserver;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Collections;

import static io.muserver.FieldBlockEncoderTest.bytesToHex;
import static io.muserver.FieldBlockEncoderTest.hexToByteArray;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;

@DisplayName("RFC 7541 6.2.3 Literal Header Field Never Indexed")
class RFC7541_6_2_3_LiteralHeaderFieldNeverIndexedTest {

    @Test
    void neverIndexedHeadersDoNotEnterTheDynamicTable() throws Exception {
        HpackTable table = new HpackTable(4096);
        FieldBlockDecoder decoder = new FieldBlockDecoder(table, 8192, 8192 * 4);

        FieldBlock block = decoder.decodeFrom(ByteBuffer.wrap(hexToByteArray("100870617373776f726406736563726574")));

        assertThat(block.entries(), hasSize(1));
        assertThat(block.get("password"), equalTo("secret"));
        assertThat(table.dynamicTableSizeInBytes(), equalTo(0));
        assertThat(
            block.lineIterator().iterator().next().neverIndexed(),
            equalTo(true)
        );
        assertThat(block.toString(), not(containsString("secret")));
        assertThat(block.toString(), containsString("password: (hidden)"));
        assertThat(
            block.toString(Collections.emptyList()),
            containsString("password: secret")
        );
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

    @Test
    void anExactStaticTableMatchStillUsesTheNeverIndexedRepresentation()
        throws Exception {
        HpackTable table = new HpackTable(4096);
        FieldBlockDecoder decoder = new FieldBlockDecoder(
            table,
            8192,
            8192 * 4
        );
        FieldBlockEncoder encoder = new FieldBlockEncoder(table);

        FieldBlock block = decoder.decodeFrom(
            ByteBuffer.wrap(hexToByteArray("1f0800"))
        );

        try (var out = new ByteArrayOutputStream()) {
            encoder.encodeTo(block, out);
            assertThat(bytesToHex(out.toByteArray()), equalTo("1f0800"));
        }
    }
}
