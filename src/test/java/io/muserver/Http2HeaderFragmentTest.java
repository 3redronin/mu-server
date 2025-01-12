package io.muserver;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class Http2HeaderFragmentTest {

    @Test
    void emptyHeadersAreTinyYo() throws Http2Exception, IOException {
        var frameHeader = new Http2FrameHeader(0, Http2FrameType.HEADERS, 0b00000101, 1);
        var headers = Http2HeadersFrame.readLogicalFrame(frameHeader, getFieldBlockDecoder(), ByteBuffer.allocate(0), InputStream.nullInputStream());
        assertThat(headers.endStream(), equalTo(true));
        assertThat(headers.exclusive(), equalTo(false));
        assertThat(headers.streamDependencyId(), equalTo(0));
        assertThat(headers.weight(), equalTo(0));
        assertThat(headers.headers().iterator().hasNext(), equalTo(false));
        // this is not actually valid as there are no pseudo headers but something else can deal with that
    }

    @NonNull
    private static FieldBlockDecoder getFieldBlockDecoder() {
        return new FieldBlockDecoder(new HpackTable(4096), 8192, 4 * 8192);
    }

    @Test
    @Disabled("Not sure it will work like this")
    void getWithHostAndAcceptCanBeParsed() throws Http2Exception, IOException {

        /*
        The HTTP1 equiv:
        GET /resource HTTP/1.1
        Host: example.org
        Accept: image/jpeg
         */

        ByteBuffer byteBuffer = ByteBuffer.allocate(256);

        // :method = GET (static table index 2)
        byteBuffer.put((byte) INDEX_METHOD_GET);   // Indexed Header Field for ":method = GET"

        // :scheme = https (static table index 7)
        byteBuffer.put((byte) INDEX_SCHEME_HTTPS); // Indexed Header Field for ":scheme = https"

        // :authority = example.org
        byteBuffer.put((byte) INDEX_AUTHORITY);    // Indexed Header Field with Literal Name
        String authority = "example.org";
        encodeLiteralHeader(byteBuffer, authority);

        // :path = /resource
        encodeLiteralHeader(byteBuffer, ":path");
        encodeLiteralHeader(byteBuffer, "/resource");

        // host = example.org (not in static table, so this is a literal header field without indexing)
        byteBuffer.put((byte) 0x00);               // Literal Header Field without indexing for "host"
        String host = "example.org";
        encodeLiteralHeader(byteBuffer, host);

        // accept = image/jpeg (not in static table, so this is a literal header field without indexing)
        byteBuffer.put((byte) 0x00);               // Literal Header Field without indexing for "accept"
        String accept = "image/jpeg";
        encodeLiteralHeader(byteBuffer, accept);


        byteBuffer.flip();
        var frameHeader = new Http2FrameHeader(byteBuffer.remaining(), Http2FrameType.HEADERS, 0b00000101, 1);
        var headers = Http2HeadersFrame.readLogicalFrame(frameHeader, getFieldBlockDecoder(), byteBuffer, InputStream.nullInputStream());
        assertThat(headers.endStream(), equalTo(true));
        assertThat(headers.exclusive(), equalTo(false));
        assertThat(headers.streamDependencyId(), equalTo(0));
        assertThat(headers.weight(), equalTo(0));
        assertThat(headers.headers().entries(), hasSize(6));
        assertThat(headers.headers().get(":method"), equalTo("GET"));
        assertThat(headers.headers().get(":scheme"), equalTo("HTTPS"));


    }

    private static void encodeLiteralHeader(ByteBuffer buffer, String value) {
        // Write length of the string first (length is 1 byte)
        buffer.put((byte) value.length());

        // Write the UTF-8 encoded string (without Huffman encoding)
        buffer.put(value.getBytes());
    }

    private static final int INDEX_METHOD_GET = 0x82;      // :method = GET (indexed representation from static table)
    private static final int INDEX_SCHEME_HTTPS = 0x87;    // :scheme = https
    private static final int INDEX_AUTHORITY = 0b10000001;       // :authority
    private static final int INDEX_PATH = 0x04;            // :path = (literal header field with indexing)
    private static final int INDEX_HOST = 0x00;            // host = (literal header field without indexing)
    private static final int INDEX_ACCEPT = 0x00;          // accept = (literal header field without indexing)

}
