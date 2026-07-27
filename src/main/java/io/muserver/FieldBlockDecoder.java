package io.muserver;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

class FieldBlockDecoder {

    private final int maxUriLength;
    private final int maxHeadersSize;
    private final HpackTable table;
    private int allowedMaxTableSize;

    FieldBlockDecoder(HpackTable table, int maxUriLength, int maxHeadersSize) {
        this.table = table;
        this.maxUriLength = maxUriLength;
        this.maxHeadersSize = maxHeadersSize;
        this.allowedMaxTableSize = table.maxSize();
    }

    FieldBlock decodeFrom(ByteBuffer buffer) throws HttpException, Http2Exception {
        try {
            return decodeCompleteBlock(buffer);
        } catch (BufferUnderflowException truncated) {
            throw new Http2Exception(
                Http2ErrorCode.COMPRESSION_ERROR,
                "truncated HPACK field block"
            );
        }
    }

    private FieldBlock decodeCompleteBlock(ByteBuffer buffer)
        throws HttpException, Http2Exception {
        var fb = new FieldBlock();
        int totalLen = 0;
        int uriLen = 0;
        boolean canChangeTableSize = true;

        while (buffer.hasRemaining()) {
            byte b = buffer.get();

            if ((b & 0b10000000) > 0) {
                // RFC7541 6.1. Indexed Header Field Representation
                canChangeTableSize = false;
                int index = readHpackInt(7, b, buffer);
                var line = table.getValue(index);
                if (HeaderNames.PSEUDO_PATH.equals(line.name())) {
                    uriLen += line.value().length();
                }
                totalLen += line.length();
                if (totalLen <= maxHeadersSize) {
                    fb.add(line);
                }
            } else {
                // RFC7541 6.3. Dynamic Table Size Update
                boolean tableSizeUpdate = (b & 0b11100000) == 0b00100000;
                // RFC7541 6.2.1. Literal Header Field with Incremental Indexing
                boolean litWith = (b & 0b01000000) > 0;
                // RFC7541 6.2.2. Literal Header Field without Indexing
                boolean litWithout = (b & 0b11110000) == 0;
                // RFC7541 6.2.3. Literal Header Field Never Indexed
                boolean litNever = (b & 0b11110000) == 0b00010000;

                if (tableSizeUpdate) {
                    if (!canChangeTableSize) {
                        throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR, "dynamic table size update must be at the start of the field block");
                    }
                    int newSize = readHpackInt(5, b, buffer);
                    if (newSize > allowedMaxTableSize) {
                        throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR, "dynamic table size update exceeds the configured maximum");
                    }
                    table.changeMaxSize(newSize);
                } else if (litWith || litWithout || litNever) {
                    canChangeTableSize = false;
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

                    boolean isUri = HeaderNames.PSEUDO_PATH.equals(name);
                    HeaderString value = readHeaderString(buffer, HeaderString.Type.VALUE);
                    if (isUri) {
                        uriLen += value.length();
                    }

                    totalLen += name.length() + value.length();

                    var line = new FieldLine(name, value, litNever);
                    if (totalLen <= maxHeadersSize) {
                        fb.add(line);
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

        int shift = 0;
        long value = I;

        // repeat
        int B;
        do {
            // B = next octet
            if (!buffer.hasRemaining()) {
                throw new Http2Exception(
                    Http2ErrorCode.COMPRESSION_ERROR,
                    "truncated HPACK integer"
                );
            }
            B = buffer.get() & 0xFF;

            // I = I + (B & 127) * 2^M
            value += (long) (B & 127) << shift;
            if (value > Integer.MAX_VALUE) {
                throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR, "hpack integer overflow");
            }

            shift += 7;
            if (shift >= 35 && (B & 128) == 128) {
                // Five continuation octets can represent every non-negative
                // Java int. RFC 7541 Section 5.1 requires encodings beyond an
                // implementation's value or octet-length limit to fail.
                throw new Http2Exception(
                    Http2ErrorCode.COMPRESSION_ERROR,
                    "hpack integer too long"
                );
            }
        } while ((B & 128) == 128);
        return (int) value;
    }

    private static HeaderString readHeaderString(ByteBuffer buffer, HeaderString.Type type) throws Http2Exception {
        if (!buffer.hasRemaining()) {
            throw new Http2Exception(
                Http2ErrorCode.COMPRESSION_ERROR,
                "missing HPACK string length"
            );
        }
        byte decl = buffer.get();
        int codeLen = readHpackInt(7, decl, buffer);
        if (codeLen > buffer.remaining()) {
            throw new Http2Exception(
                Http2ErrorCode.COMPRESSION_ERROR,
                "HPACK string exceeds the field block"
            );
        }
        if ((decl & 0b10000000) > 0) {
            return HuffmanDecoder.decodeFrom(buffer, codeLen, type);
        } else {
            var nameBuf = new byte[codeLen];
            buffer.get(nameBuf);
            return HeaderString.valueOf(nameBuf, type);
        }
    }

    public void changeTableSize(int headerTableSize) {
        this.allowedMaxTableSize = headerTableSize;
        table.changeMaxSize(headerTableSize);
    }
}
