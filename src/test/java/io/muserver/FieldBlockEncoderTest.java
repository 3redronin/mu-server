package io.muserver;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static io.muserver.FieldBlockEncoder.writeHpackInt;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

class FieldBlockEncoderTest {

    private final HpackTable table = new HpackTable(4096);
    private final FieldBlockEncoder encoder = new FieldBlockEncoder(table);
    private final FieldBlockDecoder decoder = new FieldBlockDecoder(table, 8192, 4 * 8192);

    @Test
    public void rfc7541ExampleC_2_1_literalHeaderWithIndexing() throws IOException, Http2Exception {
        byte[] array = hexToByteArray("400a637573746f6d2d6b65790d637573746f6d2d686561646572");
        var decoded = decoder.decodeFrom(ByteBuffer.wrap(array));
        assertThat(decoded.entries(), hasSize(1));
        assertThat(decoded.get("custom-key"), equalTo("custom-header"));
        assertThat(table.getValue(62), equalTo(new FieldLine(HeaderString.valueOf("custom-key", HeaderString.Type.HEADER), HeaderString.valueOf("custom-header", HeaderString.Type.VALUE))));
        assertThat(table.dynamicTableSizeInBytes(), equalTo(55));

        assertThat(roundTrip(decoded), equalTo(decoded));
        assertThat(table.getValue(62), equalTo(new FieldLine(HeaderString.valueOf("custom-key", HeaderString.Type.HEADER), HeaderString.valueOf("custom-header", HeaderString.Type.VALUE))));
        assertThat(table.dynamicTableSizeInBytes(), equalTo(55));
    }

    @Test
    public void rfc7541ExampleC_2_2_literalHeaderWithoutIndexing() throws IOException, Http2Exception {
        byte[] array = hexToByteArray("040c 2f73 616d 706c 652f 7061 7468");
        var decoded = decoder.decodeFrom(ByteBuffer.wrap(array));
        assertThat(decoded.entries(), hasSize(1));
        assertThat(decoded.get(":path"), equalTo("/sample/path"));
        assertThat(table.dynamicTableSizeInBytes(), equalTo(0));

        assertThat(roundTrip(decoded), equalTo(decoded));
        assertThat(table.dynamicTableSizeInBytes(), equalTo(0));
    }

    @Test
    public void rfc7541ExampleC_2_3_literalHeaderNeverIndexed() throws IOException, Http2Exception {

        byte[] array = hexToByteArray("1008 7061 7373 776f 7264 0673 6563 7265 74");
        var decoded = decoder.decodeFrom(ByteBuffer.wrap(array));
        assertThat(decoded.entries(), hasSize(1));
        assertThat(decoded.get("password"), equalTo("secret"));
        assertThat(table.dynamicTableSizeInBytes(), equalTo(0));

        assertThat(roundTrip(decoded), equalTo(decoded));
        assertThat(table.dynamicTableSizeInBytes(), equalTo(0));

        assertThat(toHex(decoded), equalTo("100870617373776f726406736563726574"));
    }

    @Test
    public void rfc7541ExampleC_2_4_indexedHeaderField() throws IOException, Http2Exception {
        byte[] array = hexToByteArray("82");
        var decoded = decoder.decodeFrom(ByteBuffer.wrap(array));
        assertThat(decoded.entries(), hasSize(1));
        assertThat(decoded.get(":method"), equalTo("GET"));
        assertThat(table.dynamicTableSizeInBytes(), equalTo(0));

        assertThat(roundTrip(decoded), equalTo(decoded));
        assertThat(table.dynamicTableSizeInBytes(), equalTo(0));
    }


    private String toHex(FieldBlock block) throws IOException {
        try (var out = new ByteArrayOutputStream()) {
            encoder.encodeTo(block, out);
            return bytesToHex(out.toByteArray());
        }
    }

    private static final byte[] HEX_ARRAY = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
    public static String bytesToHex(byte[] bytes) {
        byte[] hexChars = new byte[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars, StandardCharsets.UTF_8);
    }
    public static byte[] hexToByteArray(String s) {
        s = s.toUpperCase().replace(" ", "");
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                + Character.digit(s.charAt(i+1), 16));
        }
        return data;
    }

    private FieldBlock roundTrip(FieldBlock original) throws IOException, Http2Exception {
        try (var out = new ByteArrayOutputStream()) {
            encoder.encodeTo(original, out);
            return decoder.decodeFrom(ByteBuffer.wrap(out.toByteArray()));
        }
    }


    @Test
    void canEncodeSmallNumbers() throws IOException {
        assertThat(writeOneByte(7, (byte)0b10011001, 1), equalTo(0b10000001));
        assertThat(writeOneByte(7, (byte)0b00100001, 1), equalTo(0b00000001));
        assertThat(writeOneByte(7, (byte)0b10011001, 9), equalTo(0b10001001));
        assertThat(writeOneByte(7, (byte)0b00101001, 9), equalTo(0b00001001));
        assertThat(writeOneByte(6, (byte)0b10101001, 9), equalTo(0b10001001));
        assertThat(writeOneByte(6, (byte)0b00011001, 9), equalTo(0b00001001));
        assertThat(writeOneByte(5, (byte)0b10001101, 9), equalTo(0b10001001));
        assertThat(writeOneByte(5, (byte)0b00001011, 9), equalTo(0b00001001));
        assertThat(writeOneByte(4, (byte)0b10001001, 9), equalTo(0b10001001));
        assertThat(writeOneByte(4, (byte)0b00001001, 9), equalTo(0b00001001));
        assertThat(writeOneByte(3, (byte)0b10001001, 1), equalTo(0b10001001));
        assertThat(writeOneByte(3, (byte)0b00001001, 1), equalTo(0b00001001));
        assertThat(writeOneByte(8, (byte)0b01111111, 127), equalTo(0b01111111));
        assertThat(writeOneByte(8, (byte)0b11111111, 255), equalTo(0b11111111));
        assertThat(writeOneByte(7, (byte)0b11111111, 127), equalTo(0b11111111));
        assertThat(writeOneByte(3, (byte)0b01010111, 7), equalTo(0b01010111));
    }

    @Test
    void canEncodeLargerNumbers() throws IOException {
        assertThat(writeBytes(5, (byte)0b10101010, 1337), contains((byte)0b10111111, (byte)0b10011010, (byte)0b00001010));
        assertThat(writeBytes(8, (byte)0b10101010, 42), contains((byte)0b00101010));
        assertThat(writeBytes(8, (byte)0b11101011, Integer.MAX_VALUE), contains((byte)-1, (byte)-128, (byte)-2, (byte)-1, (byte)-1, (byte)7));
        assertThat(writeBytes(1, (byte)0b10101010, Integer.MAX_VALUE), contains((byte)-85, (byte)-2, (byte)-1, (byte)-1, (byte)-1, (byte)7));
    }

    @Test
    void canEncodeWithPrefix7() throws IOException {
        assertThat(writeBytes(7, (byte)127, 26000),
            contains((byte) 127, (byte)145, (byte)202, (byte)1));
    }

    static int writeOneByte(int n, byte prefix, int value) throws IOException {
        var baos = new ByteArrayOutputStream();
        int bytesWritten = writeHpackInt(n, prefix, baos, value);
        assertThat(bytesWritten, equalTo(baos.size()));
        return baos.toByteArray()[0] & 0xFF;
    }

    static List<Byte> writeBytes(int n, byte prefix, int value) throws IOException {
        var baos = new ByteArrayOutputStream();
        int bytesWritten = writeHpackInt(n, prefix, baos, value);
        assertThat(bytesWritten, equalTo(baos.size()));
        return toByteList(baos);
    }

    @NotNull
    static ArrayList<Byte> toByteList(ByteArrayOutputStream baos) {
        var list = new ArrayList<Byte>();
        for (byte b : baos.toByteArray()) {
            list.add(b);
        }
        return list;
    }


}