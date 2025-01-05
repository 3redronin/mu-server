package io.muserver;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static io.muserver.FieldBlockDecoder.readHpackInt;
import static io.muserver.FieldBlockEncoderTest.hexToByteArray;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FieldBlockDecoderTest {

    public static final ByteBuffer empty = ByteBuffer.allocate(0);
    private final HpackTable table = new HpackTable(4096);
    private final FieldBlockDecoder decoder = new FieldBlockDecoder(table);

    @Test
    void canDecodeNBitPrefixedValues() throws Http2Exception {
        assertThat(readHpackInt(7, (byte)0b10000001, empty), equalTo(1));
        assertThat(readHpackInt(7, (byte)0b00000001, empty), equalTo(1));

        assertThat(readHpackInt(7, (byte)0b10001001, empty), equalTo(9));
        assertThat(readHpackInt(7, (byte)0b00001001, empty), equalTo(9));
        assertThat(readHpackInt(6, (byte)0b10001001, empty), equalTo(9));
        assertThat(readHpackInt(6, (byte)0b00001001, empty), equalTo(9));
        assertThat(readHpackInt(5, (byte)0b10001001, empty), equalTo(9));
        assertThat(readHpackInt(5, (byte)0b00001001, empty), equalTo(9));
        assertThat(readHpackInt(4, (byte)0b10001001, empty), equalTo(9));
        assertThat(readHpackInt(4, (byte)0b00001001, empty), equalTo(9));
        assertThat(readHpackInt(3, (byte)0b10001001, empty), equalTo(1));
        assertThat(readHpackInt(3, (byte)0b00001001, empty), equalTo(1));

        assertThat(readHpackInt(8, (byte)0b01111111, empty), equalTo(127));

    }

    @Test
    public void canDecodeMultiByteIntegers() throws Http2Exception {
        assertThat(readHpackInt(5, (byte)0b01011111, buffed((byte)0b10011010, (byte)0b00001010)), equalTo(1337));
        assertThat(readHpackInt(8, (byte)0b00101010, buffed()), equalTo(42));
        assertThat(readHpackInt(8, (byte)0b11111111, buffed((byte)0)), equalTo(255));
        assertThat(readHpackInt(7, (byte)0b11111111, buffed((byte)0)), equalTo(127));
        assertThat(readHpackInt(3, (byte)0b01010111, buffed((byte)0)), equalTo(7));

        assertThat(readHpackInt(5, (byte) 31, buffed((byte) 224, (byte) 255, (byte) 255, (byte) 255, (byte) 3)), equalTo(Integer.MAX_VALUE / 2));
        assertThat(readHpackInt(5, (byte) 31, buffed((byte) 224, (byte) 255, (byte) 255, (byte) 255, (byte) 7)), equalTo(Integer.MAX_VALUE));
    }

    @Test
    void canEncodeWithPrefix7() throws Http2Exception {
        assertThat(readHpackInt(7, (byte)127,
            buffed((byte)145, (byte)202, (byte)1)), equalTo(26000));
    }

    @Test
    public void http2ExceptionIfTooManyBytes() throws Http2Exception {
        var ex = assertThrows(Http2Exception.class, () -> readHpackInt(8, (byte) 0b11111111, buffed(
            // each of these just adds 0, so an attacker can just send a whole load to waste precious time
            (byte) 0b10000000,
            (byte) 0b10000000,
            (byte) 0b10000000,
            (byte) 0b10000000,
            (byte) 0b10000000,
            (byte) 0b10000000,
            (byte) 0b10000000,
            (byte) 0b10000000,
            (byte) 0b10000000,
            (byte) 0b10000000,
            (byte) 0b10000000,
            (byte) 0b00000000)));
        assertThat(ex.getMessage(), equalTo("hpack integer too long"));
    }

    @Test
    public void integerMaxValueIsMaxValue() throws Http2Exception {
        assertThat(readHpackInt(5, (byte) 31, buffed((byte) 224, (byte) 255, (byte) 255, (byte) 255, (byte) 7)), equalTo(Integer.MAX_VALUE));

        var ex = assertThrows(Http2Exception.class, () -> readHpackInt(8, (byte) 0b11111111,
            buffed((byte) 224, (byte) 255, (byte) 255, (byte) 255, (byte) 8)
            ));
        assertThat(ex.getMessage(), equalTo("hpack integer overflow"));
    }

    @Test
    public void rfc7541_c_3_Request_Examples_without_Huffman_Coding() throws Exception {
        /*
           C.3.1.  First Request
           :method: GET
           :scheme: http
           :path: /
           :authority: www.example.com
         */
        var firstBlock = decoder.decodeFrom(ByteBuffer.wrap(hexToByteArray(
            "8286 8441 0f77 7777 2e65 7861 6d70 6c65 2e63 6f6d")));
        assertThat(firstBlock.get(":method"), equalTo("GET"));
        assertThat(firstBlock.get(":scheme"), equalTo("http"));
        assertThat(firstBlock.get(":path"), equalTo("/"));
        assertThat(firstBlock.get(":authority"), equalTo("www.example.com"));
        assertThat(firstBlock.entries(), hasSize(4));

        assertThat(table.getValue(62), equalTo(fieldLine(":authority", "www.example.com")));
        assertThat(table.dynamicTableSizeInBytes(), equalTo(57));

        /*
        C.3.2.  Second Request
           :method: GET
           :scheme: http
           :path: /
           :authority: www.example.com
           cache-control: no-cache
         */

        var secondBlock = decoder.decodeFrom(ByteBuffer.wrap(hexToByteArray(
            "8286 84be 5808 6e6f 2d63 6163 6865")));
        assertThat(secondBlock.get(":method"), equalTo("GET"));
        assertThat(secondBlock.get(":scheme"), equalTo("http"));
        assertThat(secondBlock.get(":path"), equalTo("/"));
        assertThat(secondBlock.get(":authority"), equalTo("www.example.com"));
        assertThat(secondBlock.get("cache-control"), equalTo("no-cache"));
        assertThat(secondBlock.entries(), hasSize(5));

        assertThat(table.getValue(62), equalTo(fieldLine("cache-control", "no-cache")));
        assertThat(table.getValue(63), equalTo(fieldLine(":authority", "www.example.com")));
        assertThat(table.dynamicTableSizeInBytes(), equalTo(110));

        /*
        C.3.3.  Third Request
           :method: GET
           :scheme: https
           :path: /index.html
           :authority: www.example.com
           custom-key: custom-value
         */

        var thirdBlock = decoder.decodeFrom(ByteBuffer.wrap(hexToByteArray(
            "8287 85bf 400a 6375 7374 6f6d 2d6b 6579 0c63 7573 746f 6d2d 7661 6c75 65")));
        assertThat(thirdBlock.get(":method"), equalTo("GET"));
        assertThat(thirdBlock.get(":scheme"), equalTo("https"));
        assertThat(thirdBlock.get(":path"), equalTo("/index.html"));
        assertThat(thirdBlock.get(":authority"), equalTo("www.example.com"));
        assertThat(thirdBlock.get("custom-key"), equalTo("custom-value"));
        assertThat(thirdBlock.entries(), hasSize(5));

        assertThat(table.getValue(62), equalTo(fieldLine("custom-key", "custom-value")));
        assertThat(table.getValue(63), equalTo(fieldLine("cache-control", "no-cache")));
        assertThat(table.getValue(64), equalTo(fieldLine(":authority", "www.example.com")));
        assertThat(table.dynamicTableSizeInBytes(), equalTo(164));
    }

    @Test
    public void rfc7541_c_4_Request_Examples_with_Huffman_Coding() throws Exception {
        /*
           C.4.1.  First Request
           :method: GET
           :scheme: http
           :path: /
           :authority: www.example.com
         */
        var firstBlock = decoder.decodeFrom(ByteBuffer.wrap(hexToByteArray(
            "8286 8441 8cf1 e3c2 e5f2 3a6b a0ab 90f4 ff")));
        assertThat(firstBlock.get(":method"), equalTo("GET"));
        assertThat(firstBlock.get(":scheme"), equalTo("http"));
        assertThat(firstBlock.get(":path"), equalTo("/"));
        assertThat(firstBlock.get(":authority"), equalTo("www.example.com"));
        assertThat(firstBlock.entries(), hasSize(4));

        assertThat(table.getValue(62), equalTo(fieldLine(":authority", "www.example.com")));
        assertThat(table.dynamicTableSizeInBytes(), equalTo(57));

        /*
        C.4.2.  Second Request
           :method: GET
           :scheme: http
           :path: /
           :authority: www.example.com
           cache-control: no-cache
         */

        var secondBlock = decoder.decodeFrom(ByteBuffer.wrap(hexToByteArray(
            "8286 84be 5886 a8eb 1064 9cbf")));
        assertThat(secondBlock.get(":method"), equalTo("GET"));
        assertThat(secondBlock.get(":scheme"), equalTo("http"));
        assertThat(secondBlock.get(":path"), equalTo("/"));
        assertThat(secondBlock.get(":authority"), equalTo("www.example.com"));
        assertThat(secondBlock.get("cache-control"), equalTo("no-cache"));
        assertThat(secondBlock.entries(), hasSize(5));

        assertThat(table.getValue(62), equalTo(fieldLine("cache-control", "no-cache")));
        assertThat(table.getValue(63), equalTo(fieldLine(":authority", "www.example.com")));
        assertThat(table.dynamicTableSizeInBytes(), equalTo(110));

        /*
        C.4.3.  Third Request
           :method: GET
           :scheme: https
           :path: /index.html
           :authority: www.example.com
           custom-key: custom-value
         */

        var thirdBlock = decoder.decodeFrom(ByteBuffer.wrap(hexToByteArray(
            "8287 85bf 4088 25a8 49e9 5ba9 7d7f 8925 a849 e95b b8e8 b4bf")));
        assertThat(thirdBlock.get(":method"), equalTo("GET"));
        assertThat(thirdBlock.get(":scheme"), equalTo("https"));
        assertThat(thirdBlock.get(":path"), equalTo("/index.html"));
        assertThat(thirdBlock.get(":authority"), equalTo("www.example.com"));
        assertThat(thirdBlock.get("custom-key"), equalTo("custom-value"));
        assertThat(thirdBlock.entries(), hasSize(5));

        assertThat(table.getValue(62), equalTo(fieldLine("custom-key", "custom-value")));
        assertThat(table.getValue(63), equalTo(fieldLine("cache-control", "no-cache")));
        assertThat(table.getValue(64), equalTo(fieldLine(":authority", "www.example.com")));
        assertThat(table.dynamicTableSizeInBytes(), equalTo(164));
    }


    @NotNull
    private static FieldLine fieldLine(String name, String value) {
        return new FieldLine(HeaderString.valueOf(name, HeaderString.Type.HEADER), HeaderString.valueOf(value, HeaderString.Type.VALUE));
    }


    @NotNull
    private static ByteBuffer buffed(byte ...bytes) {
        return ByteBuffer.wrap(bytes);
    }

}