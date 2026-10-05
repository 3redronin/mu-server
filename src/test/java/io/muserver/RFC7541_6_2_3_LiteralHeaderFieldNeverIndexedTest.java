package io.muserver;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Set;

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
    void aConfiguredSensitiveNameOverridesAnExactStaticMatch() throws Exception {
        var encoder = new FieldBlockEncoder(new HpackTable(4096), Set.of("authorization"));
        var block = new FieldBlock();
        block.set("AUTHORIZATION", "");

        try (var out = new ByteArrayOutputStream()) {
            encoder.encodeTo(block, out);
            assertThat(bytesToHex(out.toByteArray()), equalTo("1f0800"));
        }
    }

    @Test
    void aConfiguredSensitiveNameOverridesAnExactDynamicMatch() throws Exception {
        var table = new HpackTable(4096);
        var block = new FieldBlock();
        block.set("x-secret", "secret");
        table.indexField(block.lineIterator().iterator().next());
        int originalSize = table.dynamicTableSizeInBytes();
        var encoder = new FieldBlockEncoder(table, Set.of("x-secret"));

        try (var out = new ByteArrayOutputStream()) {
            encoder.encodeTo(block, out);
            assertThat(bytesToHex(out.toByteArray()), equalTo("1f2f06736563726574"));
            var decoded = new FieldBlockDecoder(table, 8192, 8192 * 4).decodeFrom(ByteBuffer.wrap(out.toByteArray()));
            assertThat(decoded.lineIterator().iterator().next().neverIndexed(), equalTo(true));
            assertThat(table.dynamicTableSizeInBytes(), equalTo(originalSize));
        }
    }

    @Test
    void anEmptyPolicyAllowsOrdinaryExactMatchesButPreservesExplicitFlags() throws Exception {
        var encoder = new FieldBlockEncoder(new HpackTable(4096), Set.of());
        var block = new FieldBlock();
        block.set("authorization", "");
        block.add(new FieldLine((HeaderString) HeaderNames.AUTHORIZATION, ValidatedHeaderValue.EMPTY_VALUE, true));

        try (var out = new ByteArrayOutputStream()) {
            encoder.encodeTo(block, out);
            assertThat(bytesToHex(out.toByteArray()), equalTo("971f0800"));
        }
    }

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
