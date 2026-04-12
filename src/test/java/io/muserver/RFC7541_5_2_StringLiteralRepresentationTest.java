package io.muserver;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static io.muserver.FieldBlockEncoderTest.hexToByteArray;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("RFC 7541 5.2 String Literal Representation")
class RFC7541_5_2_StringLiteralRepresentationTest {

    @Test
    void huffmanEncodedStringsCanBeDecoded() throws Exception {
        HeaderString decoded = HuffmanDecoder.decodeFrom(ByteBuffer.wrap(
            hexToByteArray("f1e3c2e5f23a6ba0ab90f4ff")
        ), 12, HeaderString.Type.VALUE);

        assertThat(decoded.toString(), equalTo("www.example.com"));
    }

    @Test
    void eosSymbolMustNotAppearInsideAHuffmanEncodedString() {
        var ex = assertThrows(Http2Exception.class, () -> HuffmanDecoder.decodeFrom(ByteBuffer.wrap(
            hexToByteArray("ffffffff")
        ), 4, HeaderString.Type.VALUE));

        assertThat(ex.errorCode(), equalTo(Http2ErrorCode.COMPRESSION_ERROR));
        assertThat(ex.getMessage(), equalTo("EOS must not appear in a huffman encoded string"));
    }

    @Test
    void huffmanPaddingMustBeAShortPrefixOfTheEosCode() {
        var ex = assertThrows(Http2Exception.class, () -> HuffmanDecoder.decodeFrom(ByteBuffer.wrap(
            hexToByteArray("00")
        ), 1, HeaderString.Type.VALUE));

        assertThat(ex.errorCode(), equalTo(Http2ErrorCode.COMPRESSION_ERROR));
        assertThat(ex.getMessage(), equalTo("invalid huffman padding"));
    }
}


