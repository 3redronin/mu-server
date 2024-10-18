package io.muserver;

import java.nio.ByteBuffer;
import java.util.Objects;

class Http2HeaderFragment {

    private final boolean exclusive;
    private boolean endHeaders;
    private final boolean endStream;
    private final int streamDependencyId;
    private final int weight;
    private final FieldBlock headers;


    Http2HeaderFragment(boolean exclusive, boolean endHeaders, boolean endStream, int streamDependencyId, int weight, FieldBlock headers) {
        this.exclusive = exclusive;
        this.endHeaders = endHeaders;
        this.endStream = endStream;
        this.streamDependencyId = streamDependencyId;
        this.weight = weight;
        this.headers = headers;
    }

    static Http2HeaderFragment readFirstFragment(Http2FrameHeader frameHeader, HpackTable headerTable, ByteBuffer buffer) throws Http2Exception {
        // figure out the fields
        var priority = (frameHeader.flags() & 0b00100000) > 0;
        var padded = (frameHeader.flags() & 0b00001000) > 0;
        var endHeaders = (frameHeader.flags() & 0b00000100) > 0;
        var endStream = (frameHeader.flags() & 0b0000001) > 0;

        var padLength = padded ? buffer.get() & 0xff : 0;

        int hpackLength = frameHeader.length();
        if (padded) {
            hpackLength -= padLength -  /* 1 byte for the pad size field  */1;
        }

        boolean exclusive;
        int streamDependency;
        int weight;
        if (priority) {
            hpackLength -= 5;
            byte next = buffer.get();
            exclusive = (next & 0b10000000) > 0;
            streamDependency = next & 0b01111111;
            weight = buffer.get() & 0xff;
        } else {
            exclusive = false;
            streamDependency = 0;
            weight = 0;
        }

        // add name/value strings as:
        var headers = new FieldBlock();

        while (hpackLength > 0) {

            var firstByte = buffer.get();
            hpackLength--;

            var isIndexed = (firstByte & 0b10000000) > 0;
            if (isIndexed) {
                var index = firstByte & 0b01111111;
                var field = headerTable.getValue(index);
                System.out.println("field = " + field);
                if (field.value().length() == 0) {
                } else {
                    // TOOD set boolean neverIndex;
                    headers.add(field.name(), field.value());
                }
            } else {
                System.out.println("not indexed");
            }
        }



        if (padLength > 0) {
            buffer.position(buffer.position() + padLength);
        }
        return new Http2HeaderFragment(exclusive, endHeaders, endStream, streamDependency, weight, headers);
    }

    boolean appendContinuationFragment(Http2FrameHeader frameHeader, HpackTable headerTable, ByteBuffer buffer) {
        // extract more header fields and add to the existing headers

        return endHeaders;
    }

    public boolean exclusive() {
        return exclusive;
    }

    public boolean endHeaders() {
        return endHeaders;
    }

    public boolean endStream() {
        return endStream;
    }

    public int streamDependencyId() {
        return streamDependencyId;
    }

    public int weight() {
        return weight;
    }

    public FieldBlock headers() {
        return headers;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Http2HeaderFragment that = (Http2HeaderFragment) o;
        return exclusive == that.exclusive && endHeaders == that.endHeaders && endStream == that.endStream && streamDependencyId == that.streamDependencyId && weight == that.weight && Objects.equals(headers, that.headers);
    }

    @Override
    public int hashCode() {
        return Objects.hash(exclusive, endHeaders, endStream, streamDependencyId, weight, headers);
    }

    @Override
    public String toString() {
        return "Http2HeaderFragment{" +
            "priority=" + exclusive +
            ", endHeaders=" + endHeaders +
            ", endStream=" + endStream +
            ", streamDependencyId=" + streamDependencyId +
            ", weight=" + weight +
            ", headers=" + headers +
            '}';
    }
}
