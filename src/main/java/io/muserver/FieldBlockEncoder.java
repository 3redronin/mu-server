package io.muserver;

import java.io.IOException;
import java.io.OutputStream;

class FieldBlockEncoder {

    private final HpackTable table;

    FieldBlockEncoder(HpackTable table) {
        this.table = table;
    }

    void encodeTo(FieldBlock block, OutputStream out) throws IOException {
        for (FieldLine line : block.lineIterator()) {
            int lineCode = table.codeFor(line);
            if (lineCode > 0) {
                // indexed name and value
                writeHpackInt(7, (byte)0b10000000, out, lineCode);
            } else {
                int designation = table.isNeverIndex(line) ? 0b00010000 : 0b00000000;
                int headerCode = table.codeFor(line.name());
                if (headerCode >= 0) {
                    // indexed name; literal value
                    writeHpackInt(4, (byte)designation, out, headerCode);
                } else {
                    // new name to index with value
                    out.write(designation);

                    // first bit is 0 as we don't encode ye olde Huffman
                    writeHpackInt(7, (byte) 0b01111111, out, line.name().length());
                    out.write(line.name().bytes);
                }

                writeHpackInt(7, (byte)0b01111111, out, line.value().length());
                out.write(line.value().bytes);
            }
        }
    }

    static int writeHpackInt(int n, byte prefix, OutputStream out, int I) throws IOException {
        // comments are the pseudo code from RFC7541 section 5.1
        int maxPrefixedSize = (1 << n) - 1;
        int prefixMask = 0xFF & (0xFF << n);
        int maskedPrefix = prefix & prefixMask;

        // if I < 2^N - 1, encode I on N bits
        if (I < maxPrefixedSize) {
            int maskedValue = I & maxPrefixedSize;
            int prefixByte = maskedPrefix | maskedValue;
            out.write(prefixByte);
            return 1;
        } else {
            // encode (2^N - 1) on N bits
            int prefixByte = maskedPrefix | maxPrefixedSize;
            out.write(prefixByte);
            int bytesWritten = 1;

            // I = I - (2^N - 1)
            I = I - maxPrefixedSize;
            while (I > 256) {
                // encode (I % 128 + 128) on 8 bits
                var next = (I % 128) + 128;
                out.write(next);
                bytesWritten++;

                I = I / 128;
            }

            // encode I on 8 bits
            out.write(I);
            return bytesWritten + 1;
        }
    }

    public void changeTableSize(int headerTableSize) {
        table.changeMaxSize(headerTableSize);
    }
}
