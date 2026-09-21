package io.muserver;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;

import static io.muserver.FieldBlockEncoderTest.hexToByteArray;
import static org.junit.jupiter.api.Assertions.*;

class HuffmanConformanceTest {
    @ParameterizedTest
    @CsvSource({"1f,a", "18ff,aa", "18c7,aaa", "18c63f,aaaa", "18c631ff,aaaaa", "18c6318f,aaaaaa",
        "18c6318c7f,aaaaaaa", "18c6318c63,aaaaaaaa", "a8eb10649cbf,no-cache", "25a849e95ba97d7f,custom-key",
        "25a849e95bb8e8b4bf,custom-value"})
    void validPaddingLengthsAndRfcExamples(String hex, String expected) throws Exception {
        // RFC 7541 Appendix B: 'a' = 00011. Repetitions exercise every padding length (0–7).
        // Remaining vectors are from Appendix C.4.
        byte[] encoded = hexToByteArray(hex);
        ByteBuffer buffer = ByteBuffer.allocate(encoded.length + 2);
        buffer.put((byte) 42).put(encoded).put((byte) 43).flip();
        buffer.get();
        assertEquals(expected, HuffmanDecoder.decodeFrom(buffer, encoded.length, HeaderString.Type.VALUE).toString());
        assertEquals(43, buffer.get(), "The next string must remain unread");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ff", "ffff", "ffffff", "ffffffff", "18", "1e", "1fff"})
    void invalidPaddingAndEosAreCompressionErrors(String hex) {
        // Section 5.2: padding is at most seven one bits; EOS is forbidden as a symbol.
        byte[] bytes = hexToByteArray(hex);
        Http2Exception error = assertThrows(Http2Exception.class,
            () -> HuffmanDecoder.decodeFrom(ByteBuffer.wrap(bytes), bytes.length, HeaderString.Type.VALUE));
        assertEquals(Http2ErrorCode.COMPRESSION_ERROR, error.errorCode());
        assertEquals(Http2Level.CONNECTION, error.errorType());
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 2})
    void invalidDeclaredLengthIsACompressionError(int length) {
        Http2Exception error = assertThrows(Http2Exception.class,
            () -> HuffmanDecoder.decodeFrom(ByteBuffer.wrap(new byte[] {0x1f}), length, HeaderString.Type.VALUE));
        assertEquals(Http2ErrorCode.COMPRESSION_ERROR, error.errorCode());
    }
}
