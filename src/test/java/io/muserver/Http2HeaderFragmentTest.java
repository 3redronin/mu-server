package io.muserver;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class Http2HeaderFragmentTest {

    @Test
    void emptyHeadersAreTinyYo() throws Http2Exception, IOException {
        var frameHeader = new Http2FrameHeader(0, Http2FrameType.HEADERS, 0b00000101, 1);
        var headers = Http2HeadersFrame.readLogicalFrame(frameHeader, getFieldBlockDecoder(), ByteBuffer.allocate(0), InputStream.nullInputStream());
        assertThat(headers.endStream(), equalTo(true));
        assertThat(headers.headers().iterator().hasNext(), equalTo(false));
        // this is not actually valid as there are no pseudo headers but something else can deal with that
    }

    @Test
    void paddedHeadersCanBeParsed() throws Exception {
        var expected = RFCTestUtils.getHelloHeaders(443);
        byte[] encoded = RFCTestUtils.encodeFieldBlock(expected);
        ByteBuffer buffer = ByteBuffer.wrap(RFCTestUtils.paddedHeadersFrame(1, true, true, encoded, 2));

        var frameHeader = Http2FrameHeader.readFrom(buffer);
        var headers = Http2HeadersFrame.readLogicalFrame(frameHeader, getFieldBlockDecoder(), buffer, InputStream.nullInputStream());

        assertThat(headers.endStream(), equalTo(true));
        assertThat(headers.headers().get(":method"), equalTo("GET"));
        assertThat(headers.headers().get(":path"), equalTo("/hello"));
        assertThat(headers.headers().get(":authority"), equalTo("localhost:443"));
    }

    @Test
    void priorityHeadersCanBeParsed() throws Exception {
        var expected = RFCTestUtils.getHelloHeaders(443);
        byte[] encoded = RFCTestUtils.encodeFieldBlock(expected);
        ByteBuffer buffer = ByteBuffer.wrap(RFCTestUtils.priorityHeadersFrame(1, true, true, encoded, true, 3, 10));

        var frameHeader = Http2FrameHeader.readFrom(buffer);
        var headers = Http2HeadersFrame.readLogicalFrame(frameHeader, getFieldBlockDecoder(), buffer, InputStream.nullInputStream());

        assertThat(headers.endStream(), equalTo(true));
        assertThat(headers.headers().get(":method"), equalTo("GET"));
        assertThat(headers.headers().get(":path"), equalTo("/hello"));
    }

    @Test
    void anEmptyInitialFragmentCanBeContinued() throws Exception {
        var expected = RFCTestUtils.getHelloHeaders(443);
        var frameHeader = new Http2FrameHeader(0, Http2FrameType.HEADERS, 0b00000001, 1);
        ByteBuffer buffer = ByteBuffer.allocate(1024).flip();
        var continuation = new ByteArrayInputStream(RFCTestUtils.continuationFrame(1, true, RFCTestUtils.encodeFieldBlock(expected)));

        var headers = Http2HeadersFrame.readLogicalFrame(frameHeader, getFieldBlockDecoder(), buffer, continuation);

        assertThat(headers.endStream(), equalTo(true));
        assertThat(headers.headers().get(":method"), equalTo("GET"));
        assertThat(headers.headers().get(":path"), equalTo("/hello"));
    }

    @Test
    void continuationAccumulationIsBoundedBeforeTheNextFragmentIsRetained() {
        var frameHeader = new Http2FrameHeader(
            5,
            Http2FrameType.HEADERS,
            0b00000001,
            1
        );
        ByteBuffer firstFragment = ByteBuffer.allocate(32);
        firstFragment.put(
            new byte[]{(byte) 0x82, (byte) 0x87, (byte) 0x84, (byte) 0x81, (byte) 0x90}
        );
        firstFragment.flip();
        var continuation = new ByteArrayInputStream(
            RFCTestUtils.continuationFrame(
                1,
                true,
                new byte[]{(byte) 0x90, (byte) 0x90}
            )
        );

        var failure = assertThrows(
            Http2Exception.class,
            () -> Http2HeadersFrame.readLogicalFrame(
                frameHeader,
                getFieldBlockDecoder(),
                firstFragment,
                continuation,
                6
            )
        );

        assertThat(failure.errorType(), equalTo(Http2Level.CONNECTION));
        assertThat(failure.errorCode(), equalTo(Http2ErrorCode.COMPRESSION_ERROR));
    }

    @Test
    void exactSizeHeaderBlocksDoNotWriteAnEmptyContinuation() throws Exception {
        FieldBlock headers = RFCTestUtils.getHelloHeaders(443);
        var encoder = new FieldBlockEncoder(new HpackTable(Http2Settings.DEFAULT_CLIENT_SETTINGS.headerTableSize));
        var encoded = new ByteArrayOutputStream();
        encoder.encodeTo(headers, encoded);

        var out = new ByteArrayOutputStream();
        new Http2HeadersFrame(1, true, headers).writeTo(new Http2Peer() {
            @Override
            public int maxFrameSize() {
                return encoded.size();
            }

            @Override
            public FieldBlockEncoder fieldBlockEncoder() {
                return new FieldBlockEncoder(new HpackTable(Http2Settings.DEFAULT_CLIENT_SETTINGS.headerTableSize));
            }
        }, out);

        ByteBuffer raw = ByteBuffer.wrap(out.toByteArray());
        var writtenHeader = Http2FrameHeader.readFrom(raw);
        assertThat(writtenHeader.frameType(), equalTo(Http2FrameType.HEADERS));
        assertThat(writtenHeader.length(), equalTo(encoded.size()));
        assertThat(raw.hasRemaining(), equalTo(true));
        raw.position(raw.position() + writtenHeader.length());
        assertThat(raw.hasRemaining(), equalTo(false));
    }

    @NonNull
    private static FieldBlockDecoder getFieldBlockDecoder() {
        return new FieldBlockDecoder(new HpackTable(4096), 8192, 4 * 8192);
    }

}
