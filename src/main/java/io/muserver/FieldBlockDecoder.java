package io.muserver;

import org.jspecify.annotations.NullMarked;

import java.nio.ByteBuffer;

@NullMarked
class FieldBlockDecoder {

    private final int maxUriLength;
    private final int maxHeadersSize;
    private final HpackTable table;

    FieldBlockDecoder(HpackTable table, int maxUriLength, int maxHeadersSize) {
        this.table = table;
        this.maxUriLength = maxUriLength;
        this.maxHeadersSize = maxHeadersSize;
    }

    FieldBlock decodeFrom(ByteBuffer buffer) throws HttpException, Http2Exception {
        var fb = new FieldBlock();
        int totalLen = 0;
        int uriLen = 0;

        while (buffer.hasRemaining()) {
            byte b = buffer.get();

            if ((b & 0b10000000) > 0) {
                // RFC7541 6.1. Indexed Header Field Representation
                int index = readHpackInt(7, b, buffer);
                fb.add(table.getValue(index));
            } else {
                // RFC7541 6.2.1. Literal Header Field with Incremental Indexing
                boolean litWith = (b & 0b01000000) > 0;
                // RFC7541 6.2.2. Literal Header Field without Indexing
                boolean litWithout = (b & 0b11110000) == 0;
                // RFC7541 6.2.3. Literal Header Field Never Indexed
                boolean litNever = (b & 0b11110000) == 0b00010000;

                if (litWith || litWithout || litNever) {
                    int prefixLen = litWith ? 6 : 4;
                    var nameIndex = readHpackInt(prefixLen, b, buffer);
                    HeaderString name;
                    if (nameIndex == 0) {
                        // new name
                        name = readHeaderString(buffer, HeaderString.Type.HEADER);
                    } else {
                        // indexed name
                        name = table.getValue(nameIndex).name();
                    }

                    boolean isUri = name == HeaderNames.PSEUDO_PATH;
                    HeaderString value = readHeaderString(buffer, HeaderString.Type.VALUE);
                    if (isUri) {
                        uriLen += value.length();
                    }

                    totalLen += name.length() + value.length();

                    var line = new FieldLine(name, value);
                    if (totalLen <= maxHeadersSize) {
                        fb.add(line);
                        if (litNever) {
                            table.neverIndex(line);
                        }
                    }
                    if (litWith) {
                        table.indexField(line);
                    }
                } else {
                    throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR, "Unrecognised field line type");
                }
            }

        }

        if (uriLen  > maxUriLength) {
            throw new HttpException(HttpStatus.URI_TOO_LONG_414);
        }
        if (totalLen > maxHeadersSize) {
            throw new HttpException(HttpStatus.REQUEST_HEADER_FIELDS_TOO_LARGE_431);
        }
        return fb;
    }

    static int readHpackInt(int n, byte prefix, ByteBuffer buffer) throws Http2Exception {
        // comments are the pseudo code from RFC7541 section 5.1

        // decode I from the next N bits
        int mask = 0xFF >> (8 - n);
        int I = prefix & mask;

        // if I < 2^N - 1, return I
        if (I < (1 << n) - 1) {
            return I;
        }

        var M = 0;

        // repeat
        int B;
        do {
            // B = next octet
            B = buffer.get() & 0xFF;

            // I = I + (B & 127) * 2^M
            I = I + ((B & 127) * (1 << M));
            if (I < 0) {
                throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR, "hpack integer overflow");
            }

            M = M + 7;
            if (M > 80) throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR, "hpack integer too long");
        } while ((B & 128) == 128);
        return I;
    }

    private static HeaderString readHeaderString(ByteBuffer buffer, HeaderString.Type type) throws Http2Exception {
        byte decl = buffer.get();
        int codeLen = readHpackInt(7, decl, buffer);
        if ((decl & 0b10000000) > 0) {
            return HuffmanDecoder.decodeFrom(buffer, codeLen, type);
        } else {
            var nameBuf = new byte[codeLen];
            buffer.get(nameBuf);
            return HeaderString.valueOf(nameBuf, type);
        }
    }

    public void changeTableSize(int headerTableSize) {
        table.changeMaxSize(headerTableSize);
    }
}
