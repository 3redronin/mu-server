package io.muserver;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static io.muserver.FieldBlockEncoderTest.hexToByteArray;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("RFC 7541 6.3 Dynamic Table Size Update")
class RFC7541_6_3_DynamicTableSizeUpdateTest {

    @Test
    void dynamicTableSizeUpdatesCanAppearAtTheStartOfAFieldBlock() throws Exception {
        HpackTable table = new HpackTable(4096);
        FieldBlockDecoder decoder = new FieldBlockDecoder(table, 8192, 8192 * 4);

        FieldBlock block = decoder.decodeFrom(ByteBuffer.wrap(hexToByteArray("3f21")));

        assertThat(table.maxSize(), equalTo(64));
        assertThat(block.size(), equalTo(0));
    }

    @Test
    void dynamicTableSizeUpdatesMustBeAtTheStartOfTheFieldBlock() {
        HpackTable table = new HpackTable(4096);
        FieldBlockDecoder decoder = new FieldBlockDecoder(table, 8192, 8192 * 4);

        var ex = assertThrows(Http2Exception.class, () -> decoder.decodeFrom(ByteBuffer.wrap(hexToByteArray("823f21"))));

        assertThat(ex.errorCode(), equalTo(Http2ErrorCode.COMPRESSION_ERROR));
        assertThat(ex.getMessage(), equalTo("dynamic table size update must be at the start of the field block"));
    }

    @Test
    void dynamicTableSizeUpdatesMustNotExceedTheConfiguredMaximum() {
        HpackTable table = new HpackTable(64);
        FieldBlockDecoder decoder = new FieldBlockDecoder(table, 8192, 8192 * 4);

        var ex = assertThrows(Http2Exception.class, () -> decoder.decodeFrom(ByteBuffer.wrap(hexToByteArray("3f45"))));

        assertThat(ex.errorCode(), equalTo(Http2ErrorCode.COMPRESSION_ERROR));
        assertThat(ex.getMessage(), equalTo("dynamic table size update exceeds the configured maximum"));
    }

    @Test
    void theConfiguredMaximumCanBeRaisedBeforeDecodingFurtherSizeUpdates() throws Exception {
        HpackTable table = new HpackTable(64);
        FieldBlockDecoder decoder = new FieldBlockDecoder(table, 8192, 8192 * 4);
        decoder.changeTableSize(128);

        decoder.decodeFrom(ByteBuffer.wrap(hexToByteArray("3f61")));

        assertThat(table.maxSize(), equalTo(128));
    }
}


