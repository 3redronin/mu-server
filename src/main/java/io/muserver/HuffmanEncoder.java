package io.muserver;

import java.io.IOException;
import java.io.OutputStream;

class HuffmanEncoder {

    public static final int INITIAL = 0;

    static void encodeTo(OutputStream out, CharSequence value) throws IOException {

        int bitidx = 0;
        int outgoing = INITIAL;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c > 255) throw new IllegalArgumentException("Non Ascii being huffman encoded");
            HuffmanChar huff = mapping[c];
            int data = huff.lsb;
            int numToWrite = huff.bitLength;

            while (numToWrite > 0) {
                int spaceInOutgoing = 8 - bitidx;
                if (spaceInOutgoing >= numToWrite) {
                    // The data is always aligned to the least significant bit in this case
                    // Example:
                    // outgoing: spaceInOutgoing=6
                    //           | 1 0 _ _ _ _ _ _ |
                    // data with numToWrite=4 (bytes 1 0 1 0):
                    // | 1 1 1 0 1 0 1 0 |
                    // need to zero out the first 8-numToWrite bytes:
                    // | 0 0 0 0 1 0 1 0 |

                    var maskedData = data & ((1 << numToWrite) - 1);

                    // then shift left by spaceInOutgoing-numToWrite bytes:
                    // | 0 0 1 0 1 0 0 0 |
                    // and OR it with outgoing
                    // | 1 0 1 0 1 0 0 0 |
                    outgoing = outgoing | (maskedData << (spaceInOutgoing - numToWrite));

                    // we were at outgoing's index 2 so add the number written for the new index
                    bitidx += numToWrite;
                    numToWrite = 0;
                    if (bitidx == 8) {
                        out.write(outgoing);
                        bitidx = 0;
                        outgoing = INITIAL;
                    }
                } else {
                    // data is too big to fit in
                    // e.g. if outgoing has bitidx 6 then spaceInOutgoing=2 and is currently:
                    // outgoing = | 1 0 1 0 1 0 _ _ |
                    // then we need the first spaceInOutgoing bits from data
                    // e.g. if data is the '#' character:
                    // | 0 0 0 0 0 0 0 0 | 0 0 0 0 0 0 0 0 | 0 0 0 0 1 1 1 1 | 1 1 1 1 1 0 1 0 |
                    // numToWrite=12 means it is ---------------here-^
                    // in this case we want to shift it to the right 10 bits, which is numToWrite-spaceInOutgoing
                    // | 0 0 0 0 0 0 0 0 | 0 0 0 0 0 0 0 0 | 0 0 0 0 0 0 0 0 | 0 0 0 0 0 0 1 1 |
                    var shiftedData = data >> (numToWrite - spaceInOutgoing);
                    // we then want to zero out any bits except the last spaceInOutgoing bits
                    // | 0 0 0 0 0 0 0 0 | 0 0 0 0 0 0 0 0 | 0 0 0 0 0 0 0 0 | 0 0 0 0 0 0 1 1 |
                    var maskedData = shiftedData & ((1 << spaceInOutgoing) - 1);

                    // and now combine it with outgoing
                    // outgoing = | 1 0 1 0 1 0 1 1 |
                    outgoing = outgoing | maskedData;
                    out.write(outgoing);
                    bitidx = 0;
                    outgoing = INITIAL;
                    numToWrite -= spaceInOutgoing;
                }

            }

        }
        if (bitidx != 0) {
            // pad the rest of the octet with 1s, which corresponds to the special EOS character
            int spaceInOutgoing = 8 - bitidx;
            int mask = (1 << spaceInOutgoing) - 1;
            outgoing = outgoing | mask;
            out.write(outgoing);
        }
    }

    static final HuffmanChar[] mapping = new HuffmanChar[257];

    static {
        mapping[0] = new HuffmanChar(0x1ff8, 13);
        mapping[1] = new HuffmanChar(0x7fffd8, 23);
        mapping[2] = new HuffmanChar(0xfffffe2, 28);
        mapping[3] = new HuffmanChar(0xfffffe3, 28);
        mapping[4] = new HuffmanChar(0xfffffe4, 28);
        mapping[5] = new HuffmanChar(0xfffffe5, 28);
        mapping[6] = new HuffmanChar(0xfffffe6, 28);
        mapping[7] = new HuffmanChar(0xfffffe7, 28);
        mapping[8] = new HuffmanChar(0xfffffe8, 28);
        mapping[9] = new HuffmanChar(0xffffea, 24);
        mapping[10] = new HuffmanChar(0x3ffffffc, 30);
        mapping[11] = new HuffmanChar(0xfffffe9, 28);
        mapping[12] = new HuffmanChar(0xfffffea, 28);
        mapping[13] = new HuffmanChar(0x3ffffffd, 30);
        mapping[14] = new HuffmanChar(0xfffffeb, 28);
        mapping[15] = new HuffmanChar(0xfffffec, 28);
        mapping[16] = new HuffmanChar(0xfffffed, 28);
        mapping[17] = new HuffmanChar(0xfffffee, 28);
        mapping[18] = new HuffmanChar(0xfffffef, 28);
        mapping[19] = new HuffmanChar(0xffffff0, 28);
        mapping[20] = new HuffmanChar(0xffffff1, 28);
        mapping[21] = new HuffmanChar(0xffffff2, 28);
        mapping[22] = new HuffmanChar(0x3ffffffe, 30);
        mapping[23] = new HuffmanChar(0xffffff3, 28);
        mapping[24] = new HuffmanChar(0xffffff4, 28);
        mapping[25] = new HuffmanChar(0xffffff5, 28);
        mapping[26] = new HuffmanChar(0xffffff6, 28);
        mapping[27] = new HuffmanChar(0xffffff7, 28);
        mapping[28] = new HuffmanChar(0xffffff8, 28);
        mapping[29] = new HuffmanChar(0xffffff9, 28);
        mapping[30] = new HuffmanChar(0xffffffa, 28);
        mapping[31] = new HuffmanChar(0xffffffb, 28);
        mapping[32] = new HuffmanChar(0x14, 6);
        mapping[33] = new HuffmanChar(0x3f8, 10);
        mapping[34] = new HuffmanChar(0x3f9, 10);
        mapping[35] = new HuffmanChar(0xffa, 12);
        mapping[36] = new HuffmanChar(0x1ff9, 13);
        mapping[37] = new HuffmanChar(0x15, 6);
        mapping[38] = new HuffmanChar(0xf8, 8);
        mapping[39] = new HuffmanChar(0x7fa, 11);
        mapping[40] = new HuffmanChar(0x3fa, 10);
        mapping[41] = new HuffmanChar(0x3fb, 10);
        mapping[42] = new HuffmanChar(0xf9, 8);
        mapping[43] = new HuffmanChar(0x7fb, 11);
        mapping[44] = new HuffmanChar(0xfa, 8);
        mapping[45] = new HuffmanChar(0x16, 6);
        mapping[46] = new HuffmanChar(0x17, 6);
        mapping[47] = new HuffmanChar(0x18, 6);
        mapping[48] = new HuffmanChar(0x0, 5);
        mapping[49] = new HuffmanChar(0x1, 5);
        mapping[50] = new HuffmanChar(0x2, 5);
        mapping[51] = new HuffmanChar(0x19, 6);
        mapping[52] = new HuffmanChar(0x1a, 6);
        mapping[53] = new HuffmanChar(0x1b, 6);
        mapping[54] = new HuffmanChar(0x1c, 6);
        mapping[55] = new HuffmanChar(0x1d, 6);
        mapping[56] = new HuffmanChar(0x1e, 6);
        mapping[57] = new HuffmanChar(0x1f, 6);
        mapping[58] = new HuffmanChar(0x5c, 7);
        mapping[59] = new HuffmanChar(0xfb, 8);
        mapping[60] = new HuffmanChar(0x7ffc, 15);
        mapping[61] = new HuffmanChar(0x20, 6);
        mapping[62] = new HuffmanChar(0xffb, 12);
        mapping[63] = new HuffmanChar(0x3fc, 10);
        mapping[64] = new HuffmanChar(0x1ffa, 13);
        mapping[65] = new HuffmanChar(0x21, 6);
        mapping[66] = new HuffmanChar(0x5d, 7);
        mapping[67] = new HuffmanChar(0x5e, 7);
        mapping[68] = new HuffmanChar(0x5f, 7);
        mapping[69] = new HuffmanChar(0x60, 7);
        mapping[70] = new HuffmanChar(0x61, 7);
        mapping[71] = new HuffmanChar(0x62, 7);
        mapping[72] = new HuffmanChar(0x63, 7);
        mapping[73] = new HuffmanChar(0x64, 7);
        mapping[74] = new HuffmanChar(0x65, 7);
        mapping[75] = new HuffmanChar(0x66, 7);
        mapping[76] = new HuffmanChar(0x67, 7);
        mapping[77] = new HuffmanChar(0x68, 7);
        mapping[78] = new HuffmanChar(0x69, 7);
        mapping[79] = new HuffmanChar(0x6a, 7);
        mapping[80] = new HuffmanChar(0x6b, 7);
        mapping[81] = new HuffmanChar(0x6c, 7);
        mapping[82] = new HuffmanChar(0x6d, 7);
        mapping[83] = new HuffmanChar(0x6e, 7);
        mapping[84] = new HuffmanChar(0x6f, 7);
        mapping[85] = new HuffmanChar(0x70, 7);
        mapping[86] = new HuffmanChar(0x71, 7);
        mapping[87] = new HuffmanChar(0x72, 7);
        mapping[88] = new HuffmanChar(0xfc, 8);
        mapping[89] = new HuffmanChar(0x73, 7);
        mapping[90] = new HuffmanChar(0xfd, 8);
        mapping[91] = new HuffmanChar(0x1ffb, 13);
        mapping[92] = new HuffmanChar(0x7fff0, 19);
        mapping[93] = new HuffmanChar(0x1ffc, 13);
        mapping[94] = new HuffmanChar(0x3ffc, 14);
        mapping[95] = new HuffmanChar(0x22, 6);
        mapping[96] = new HuffmanChar(0x7ffd, 15);
        mapping[97] = new HuffmanChar(0x3, 5);
        mapping[98] = new HuffmanChar(0x23, 6);
        mapping[99] = new HuffmanChar(0x4, 5);
        mapping[100] = new HuffmanChar(0x24, 6);
        mapping[101] = new HuffmanChar(0x5, 5);
        mapping[102] = new HuffmanChar(0x25, 6);
        mapping[103] = new HuffmanChar(0x26, 6);
        mapping[104] = new HuffmanChar(0x27, 6);
        mapping[105] = new HuffmanChar(0x6, 5);
        mapping[106] = new HuffmanChar(0x74, 7);
        mapping[107] = new HuffmanChar(0x75, 7);
        mapping[108] = new HuffmanChar(0x28, 6);
        mapping[109] = new HuffmanChar(0x29, 6);
        mapping[110] = new HuffmanChar(0x2a, 6);
        mapping[111] = new HuffmanChar(0x7, 5);
        mapping[112] = new HuffmanChar(0x2b, 6);
        mapping[113] = new HuffmanChar(0x76, 7);
        mapping[114] = new HuffmanChar(0x2c, 6);
        mapping[115] = new HuffmanChar(0x8, 5);
        mapping[116] = new HuffmanChar(0x9, 5);
        mapping[117] = new HuffmanChar(0x2d, 6);
        mapping[118] = new HuffmanChar(0x77, 7);
        mapping[119] = new HuffmanChar(0x78, 7);
        mapping[120] = new HuffmanChar(0x79, 7);
        mapping[121] = new HuffmanChar(0x7a, 7);
        mapping[122] = new HuffmanChar(0x7b, 7);
        mapping[123] = new HuffmanChar(0x7ffe, 15);
        mapping[124] = new HuffmanChar(0x7fc, 11);
        mapping[125] = new HuffmanChar(0x3ffd, 14);
        mapping[126] = new HuffmanChar(0x1ffd, 13);
        mapping[127] = new HuffmanChar(0xffffffc, 28);
        mapping[128] = new HuffmanChar(0xfffe6, 20);
        mapping[129] = new HuffmanChar(0x3fffd2, 22);
        mapping[130] = new HuffmanChar(0xfffe7, 20);
        mapping[131] = new HuffmanChar(0xfffe8, 20);
        mapping[132] = new HuffmanChar(0x3fffd3, 22);
        mapping[133] = new HuffmanChar(0x3fffd4, 22);
        mapping[134] = new HuffmanChar(0x3fffd5, 22);
        mapping[135] = new HuffmanChar(0x7fffd9, 23);
        mapping[136] = new HuffmanChar(0x3fffd6, 22);
        mapping[137] = new HuffmanChar(0x7fffda, 23);
        mapping[138] = new HuffmanChar(0x7fffdb, 23);
        mapping[139] = new HuffmanChar(0x7fffdc, 23);
        mapping[140] = new HuffmanChar(0x7fffdd, 23);
        mapping[141] = new HuffmanChar(0x7fffde, 23);
        mapping[142] = new HuffmanChar(0xffffeb, 24);
        mapping[143] = new HuffmanChar(0x7fffdf, 23);
        mapping[144] = new HuffmanChar(0xffffec, 24);
        mapping[145] = new HuffmanChar(0xffffed, 24);
        mapping[146] = new HuffmanChar(0x3fffd7, 22);
        mapping[147] = new HuffmanChar(0x7fffe0, 23);
        mapping[148] = new HuffmanChar(0xffffee, 24);
        mapping[149] = new HuffmanChar(0x7fffe1, 23);
        mapping[150] = new HuffmanChar(0x7fffe2, 23);
        mapping[151] = new HuffmanChar(0x7fffe3, 23);
        mapping[152] = new HuffmanChar(0x7fffe4, 23);
        mapping[153] = new HuffmanChar(0x1fffdc, 21);
        mapping[154] = new HuffmanChar(0x3fffd8, 22);
        mapping[155] = new HuffmanChar(0x7fffe5, 23);
        mapping[156] = new HuffmanChar(0x3fffd9, 22);
        mapping[157] = new HuffmanChar(0x7fffe6, 23);
        mapping[158] = new HuffmanChar(0x7fffe7, 23);
        mapping[159] = new HuffmanChar(0xffffef, 24);
        mapping[160] = new HuffmanChar(0x3fffda, 22);
        mapping[161] = new HuffmanChar(0x1fffdd, 21);
        mapping[162] = new HuffmanChar(0xfffe9, 20);
        mapping[163] = new HuffmanChar(0x3fffdb, 22);
        mapping[164] = new HuffmanChar(0x3fffdc, 22);
        mapping[165] = new HuffmanChar(0x7fffe8, 23);
        mapping[166] = new HuffmanChar(0x7fffe9, 23);
        mapping[167] = new HuffmanChar(0x1fffde, 21);
        mapping[168] = new HuffmanChar(0x7fffea, 23);
        mapping[169] = new HuffmanChar(0x3fffdd, 22);
        mapping[170] = new HuffmanChar(0x3fffde, 22);
        mapping[171] = new HuffmanChar(0xfffff0, 24);
        mapping[172] = new HuffmanChar(0x1fffdf, 21);
        mapping[173] = new HuffmanChar(0x3fffdf, 22);
        mapping[174] = new HuffmanChar(0x7fffeb, 23);
        mapping[175] = new HuffmanChar(0x7fffec, 23);
        mapping[176] = new HuffmanChar(0x1fffe0, 21);
        mapping[177] = new HuffmanChar(0x1fffe1, 21);
        mapping[178] = new HuffmanChar(0x3fffe0, 22);
        mapping[179] = new HuffmanChar(0x1fffe2, 21);
        mapping[180] = new HuffmanChar(0x7fffed, 23);
        mapping[181] = new HuffmanChar(0x3fffe1, 22);
        mapping[182] = new HuffmanChar(0x7fffee, 23);
        mapping[183] = new HuffmanChar(0x7fffef, 23);
        mapping[184] = new HuffmanChar(0xfffea, 20);
        mapping[185] = new HuffmanChar(0x3fffe2, 22);
        mapping[186] = new HuffmanChar(0x3fffe3, 22);
        mapping[187] = new HuffmanChar(0x3fffe4, 22);
        mapping[188] = new HuffmanChar(0x7ffff0, 23);
        mapping[189] = new HuffmanChar(0x3fffe5, 22);
        mapping[190] = new HuffmanChar(0x3fffe6, 22);
        mapping[191] = new HuffmanChar(0x7ffff1, 23);
        mapping[192] = new HuffmanChar(0x3ffffe0, 26);
        mapping[193] = new HuffmanChar(0x3ffffe1, 26);
        mapping[194] = new HuffmanChar(0xfffeb, 20);
        mapping[195] = new HuffmanChar(0x7fff1, 19);
        mapping[196] = new HuffmanChar(0x3fffe7, 22);
        mapping[197] = new HuffmanChar(0x7ffff2, 23);
        mapping[198] = new HuffmanChar(0x3fffe8, 22);
        mapping[199] = new HuffmanChar(0x1ffffec, 25);
        mapping[200] = new HuffmanChar(0x3ffffe2, 26);
        mapping[201] = new HuffmanChar(0x3ffffe3, 26);
        mapping[202] = new HuffmanChar(0x3ffffe4, 26);
        mapping[203] = new HuffmanChar(0x7ffffde, 27);
        mapping[204] = new HuffmanChar(0x7ffffdf, 27);
        mapping[205] = new HuffmanChar(0x3ffffe5, 26);
        mapping[206] = new HuffmanChar(0xfffff1, 24);
        mapping[207] = new HuffmanChar(0x1ffffed, 25);
        mapping[208] = new HuffmanChar(0x7fff2, 19);
        mapping[209] = new HuffmanChar(0x1fffe3, 21);
        mapping[210] = new HuffmanChar(0x3ffffe6, 26);
        mapping[211] = new HuffmanChar(0x7ffffe0, 27);
        mapping[212] = new HuffmanChar(0x7ffffe1, 27);
        mapping[213] = new HuffmanChar(0x3ffffe7, 26);
        mapping[214] = new HuffmanChar(0x7ffffe2, 27);
        mapping[215] = new HuffmanChar(0xfffff2, 24);
        mapping[216] = new HuffmanChar(0x1fffe4, 21);
        mapping[217] = new HuffmanChar(0x1fffe5, 21);
        mapping[218] = new HuffmanChar(0x3ffffe8, 26);
        mapping[219] = new HuffmanChar(0x3ffffe9, 26);
        mapping[220] = new HuffmanChar(0xffffffd, 28);
        mapping[221] = new HuffmanChar(0x7ffffe3, 27);
        mapping[222] = new HuffmanChar(0x7ffffe4, 27);
        mapping[223] = new HuffmanChar(0x7ffffe5, 27);
        mapping[224] = new HuffmanChar(0xfffec, 20);
        mapping[225] = new HuffmanChar(0xfffff3, 24);
        mapping[226] = new HuffmanChar(0xfffed, 20);
        mapping[227] = new HuffmanChar(0x1fffe6, 21);
        mapping[228] = new HuffmanChar(0x3fffe9, 22);
        mapping[229] = new HuffmanChar(0x1fffe7, 21);
        mapping[230] = new HuffmanChar(0x1fffe8, 21);
        mapping[231] = new HuffmanChar(0x7ffff3, 23);
        mapping[232] = new HuffmanChar(0x3fffea, 22);
        mapping[233] = new HuffmanChar(0x3fffeb, 22);
        mapping[234] = new HuffmanChar(0x1ffffee, 25);
        mapping[235] = new HuffmanChar(0x1ffffef, 25);
        mapping[236] = new HuffmanChar(0xfffff4, 24);
        mapping[237] = new HuffmanChar(0xfffff5, 24);
        mapping[238] = new HuffmanChar(0x3ffffea, 26);
        mapping[239] = new HuffmanChar(0x7ffff4, 23);
        mapping[240] = new HuffmanChar(0x3ffffeb, 26);
        mapping[241] = new HuffmanChar(0x7ffffe6, 27);
        mapping[242] = new HuffmanChar(0x3ffffec, 26);
        mapping[243] = new HuffmanChar(0x3ffffed, 26);
        mapping[244] = new HuffmanChar(0x7ffffe7, 27);
        mapping[245] = new HuffmanChar(0x7ffffe8, 27);
        mapping[246] = new HuffmanChar(0x7ffffe9, 27);
        mapping[247] = new HuffmanChar(0x7ffffea, 27);
        mapping[248] = new HuffmanChar(0x7ffffeb, 27);
        mapping[249] = new HuffmanChar(0xffffffe, 28);
        mapping[250] = new HuffmanChar(0x7ffffec, 27);
        mapping[251] = new HuffmanChar(0x7ffffed, 27);
        mapping[252] = new HuffmanChar(0x7ffffee, 27);
        mapping[253] = new HuffmanChar(0x7ffffef, 27);
        mapping[254] = new HuffmanChar(0x7fffff0, 27);
        mapping[255] = new HuffmanChar(0x3ffffee, 26);
        mapping[256] = new HuffmanChar(0x3fffffff, 30);
    }


    static class HuffmanChar {
        final int lsb;
        final int bitLength;
        private HuffmanChar(int lsb, int bitLength) {
            this.lsb = lsb;
            this.bitLength = bitLength;
        }
    }

}
