package io.muserver;

import java.io.IOException;
import java.io.OutputStream;

class HuffmanEncoder {

    public static final int INITIAL = 0;
    static void encodeTo2(OutputStream out, CharSequence value) throws IOException {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
//            if (c > 255) throw new IllegalArgumentException("Non Ascii being huffman encoded");
            HuffmanChar huff = mapping[c];
            int data = huff.lsb;
            int numToWrite = huff.bitLength;

        }

    }

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
        mapping[0] = new HuffmanChar(0b1111111111000, 13);
        mapping[1] = new HuffmanChar(0b11111111111111111011000, 23);
        mapping[2] = new HuffmanChar(0b11111111111111111111111000, 26);
        mapping[3] = new HuffmanChar(0b11111111111111111111111000, 26);
        mapping[4] = new HuffmanChar(0b11111111111111111111111001, 26);
        mapping[5] = new HuffmanChar(0b11111111111111111111111001, 26);
        mapping[6] = new HuffmanChar(0b11111111111111111111111001, 26);
        mapping[7] = new HuffmanChar(0b11111111111111111111111001, 26);
        mapping[8] = new HuffmanChar(0b11111111111111111111111010, 26);
        mapping[9] = new HuffmanChar(0b111111111111111111101010, 24);
        mapping[10] = new HuffmanChar(0b11111111111111111111111111, 26);
        mapping[11] = new HuffmanChar(0b11111111111111111111111010, 26);
        mapping[12] = new HuffmanChar(0b11111111111111111111111010, 26);
        mapping[13] = new HuffmanChar(0b11111111111111111111111111, 26);
        mapping[14] = new HuffmanChar(0b11111111111111111111111010, 26);
        mapping[15] = new HuffmanChar(0b11111111111111111111111011, 26);
        mapping[16] = new HuffmanChar(0b11111111111111111111111011, 26);
        mapping[17] = new HuffmanChar(0b11111111111111111111111011, 26);
        mapping[18] = new HuffmanChar(0b11111111111111111111111011, 26);
        mapping[19] = new HuffmanChar(0b11111111111111111111111100, 26);
        mapping[20] = new HuffmanChar(0b11111111111111111111111100, 26);
        mapping[21] = new HuffmanChar(0b11111111111111111111111100, 26);
        mapping[22] = new HuffmanChar(0b11111111111111111111111111, 26);
        mapping[23] = new HuffmanChar(0b11111111111111111111111100, 26);
        mapping[24] = new HuffmanChar(0b11111111111111111111111101, 26);
        mapping[25] = new HuffmanChar(0b11111111111111111111111101, 26);
        mapping[26] = new HuffmanChar(0b11111111111111111111111101, 26);
        mapping[27] = new HuffmanChar(0b11111111111111111111111101, 26);
        mapping[28] = new HuffmanChar(0b11111111111111111111111110, 26);
        mapping[29] = new HuffmanChar(0b11111111111111111111111110, 26);
        mapping[30] = new HuffmanChar(0b11111111111111111111111110, 26);
        mapping[31] = new HuffmanChar(0b11111111111111111111111110, 26);
        mapping[32] = new HuffmanChar(0b010100, 6);
        mapping[33] = new HuffmanChar(0b1111111000, 10);
        mapping[34] = new HuffmanChar(0b1111111001, 10);
        mapping[35] = new HuffmanChar(0b111111111010, 12);
        mapping[36] = new HuffmanChar(0b1111111111001, 13);
        mapping[37] = new HuffmanChar(0b010101, 6);
        mapping[38] = new HuffmanChar(0b11111000, 8);
        mapping[39] = new HuffmanChar(0b11111111010, 11);
        mapping[40] = new HuffmanChar(0b1111111010, 10);
        mapping[41] = new HuffmanChar(0b1111111011, 10);
        mapping[42] = new HuffmanChar(0b11111001, 8);
        mapping[43] = new HuffmanChar(0b11111111011, 11);
        mapping[44] = new HuffmanChar(0b11111010, 8);
        mapping[45] = new HuffmanChar(0b010110, 6);
        mapping[46] = new HuffmanChar(0b010111, 6);
        mapping[47] = new HuffmanChar(0b011000, 6);
        mapping[48] = new HuffmanChar(0b00000, 5);
        mapping[49] = new HuffmanChar(0b00001, 5);
        mapping[50] = new HuffmanChar(0b00010, 5);
        mapping[51] = new HuffmanChar(0b011001, 6);
        mapping[52] = new HuffmanChar(0b011010, 6);
        mapping[53] = new HuffmanChar(0b011011, 6);
        mapping[54] = new HuffmanChar(0b011100, 6);
        mapping[55] = new HuffmanChar(0b011101, 6);
        mapping[56] = new HuffmanChar(0b011110, 6);
        mapping[57] = new HuffmanChar(0b011111, 6);
        mapping[58] = new HuffmanChar(0b1011100, 7);
        mapping[59] = new HuffmanChar(0b11111011, 8);
        mapping[60] = new HuffmanChar(0b111111111111100, 15);
        mapping[61] = new HuffmanChar(0b100000, 6);
        mapping[62] = new HuffmanChar(0b111111111011, 12);
        mapping[63] = new HuffmanChar(0b1111111100, 10);
        mapping[64] = new HuffmanChar(0b1111111111010, 13);
        mapping[65] = new HuffmanChar(0b100001, 6);
        mapping[66] = new HuffmanChar(0b1011101, 7);
        mapping[67] = new HuffmanChar(0b1011110, 7);
        mapping[68] = new HuffmanChar(0b1011111, 7);
        mapping[69] = new HuffmanChar(0b1100000, 7);
        mapping[70] = new HuffmanChar(0b1100001, 7);
        mapping[71] = new HuffmanChar(0b1100010, 7);
        mapping[72] = new HuffmanChar(0b1100011, 7);
        mapping[73] = new HuffmanChar(0b1100100, 7);
        mapping[74] = new HuffmanChar(0b1100101, 7);
        mapping[75] = new HuffmanChar(0b1100110, 7);
        mapping[76] = new HuffmanChar(0b1100111, 7);
        mapping[77] = new HuffmanChar(0b1101000, 7);
        mapping[78] = new HuffmanChar(0b1101001, 7);
        mapping[79] = new HuffmanChar(0b1101010, 7);
        mapping[80] = new HuffmanChar(0b1101011, 7);
        mapping[81] = new HuffmanChar(0b1101100, 7);
        mapping[82] = new HuffmanChar(0b1101101, 7);
        mapping[83] = new HuffmanChar(0b1101110, 7);
        mapping[84] = new HuffmanChar(0b1101111, 7);
        mapping[85] = new HuffmanChar(0b1110000, 7);
        mapping[86] = new HuffmanChar(0b1110001, 7);
        mapping[87] = new HuffmanChar(0b1110010, 7);
        mapping[88] = new HuffmanChar(0b11111100, 8);
        mapping[89] = new HuffmanChar(0b1110011, 7);
        mapping[90] = new HuffmanChar(0b11111101, 8);
        mapping[91] = new HuffmanChar(0b1111111111011, 13);
        mapping[92] = new HuffmanChar(0b1111111111111110000, 19);
        mapping[93] = new HuffmanChar(0b1111111111100, 13);
        mapping[94] = new HuffmanChar(0b11111111111100, 14);
        mapping[95] = new HuffmanChar(0b100010, 6);
        mapping[96] = new HuffmanChar(0b111111111111101, 15);
        mapping[97] = new HuffmanChar(0b00011, 5);
        mapping[98] = new HuffmanChar(0b100011, 6);
        mapping[99] = new HuffmanChar(0b00100, 5);
        mapping[100] = new HuffmanChar(0b100100, 6);
        mapping[101] = new HuffmanChar(0b00101, 5);
        mapping[102] = new HuffmanChar(0b100101, 6);
        mapping[103] = new HuffmanChar(0b100110, 6);
        mapping[104] = new HuffmanChar(0b100111, 6);
        mapping[105] = new HuffmanChar(0b00110, 5);
        mapping[106] = new HuffmanChar(0b1110100, 7);
        mapping[107] = new HuffmanChar(0b1110101, 7);
        mapping[108] = new HuffmanChar(0b101000, 6);
        mapping[109] = new HuffmanChar(0b101001, 6);
        mapping[110] = new HuffmanChar(0b101010, 6);
        mapping[111] = new HuffmanChar(0b00111, 5);
        mapping[112] = new HuffmanChar(0b101011, 6);
        mapping[113] = new HuffmanChar(0b1110110, 7);
        mapping[114] = new HuffmanChar(0b101100, 6);
        mapping[115] = new HuffmanChar(0b01000, 5);
        mapping[116] = new HuffmanChar(0b01001, 5);
        mapping[117] = new HuffmanChar(0b101101, 6);
        mapping[118] = new HuffmanChar(0b1110111, 7);
        mapping[119] = new HuffmanChar(0b1111000, 7);
        mapping[120] = new HuffmanChar(0b1111001, 7);
        mapping[121] = new HuffmanChar(0b1111010, 7);
        mapping[122] = new HuffmanChar(0b1111011, 7);
        mapping[123] = new HuffmanChar(0b111111111111110, 15);
        mapping[124] = new HuffmanChar(0b11111111100, 11);
        mapping[125] = new HuffmanChar(0b11111111111101, 14);
        mapping[126] = new HuffmanChar(0b1111111111101, 13);
        mapping[127] = new HuffmanChar(0b11111111111111111111111111, 26);
        mapping[128] = new HuffmanChar(0b11111111111111100110, 20);
        mapping[129] = new HuffmanChar(0b1111111111111111010010, 22);
        mapping[130] = new HuffmanChar(0b11111111111111100111, 20);
        mapping[131] = new HuffmanChar(0b11111111111111101000, 20);
        mapping[132] = new HuffmanChar(0b1111111111111111010011, 22);
        mapping[133] = new HuffmanChar(0b1111111111111111010100, 22);
        mapping[134] = new HuffmanChar(0b1111111111111111010101, 22);
        mapping[135] = new HuffmanChar(0b11111111111111111011001, 23);
        mapping[136] = new HuffmanChar(0b1111111111111111010110, 22);
        mapping[137] = new HuffmanChar(0b11111111111111111011010, 23);
        mapping[138] = new HuffmanChar(0b11111111111111111011011, 23);
        mapping[139] = new HuffmanChar(0b11111111111111111011100, 23);
        mapping[140] = new HuffmanChar(0b11111111111111111011101, 23);
        mapping[141] = new HuffmanChar(0b11111111111111111011110, 23);
        mapping[142] = new HuffmanChar(0b111111111111111111101011, 24);
        mapping[143] = new HuffmanChar(0b11111111111111111011111, 23);
        mapping[144] = new HuffmanChar(0b111111111111111111101100, 24);
        mapping[145] = new HuffmanChar(0b111111111111111111101101, 24);
        mapping[146] = new HuffmanChar(0b1111111111111111010111, 22);
        mapping[147] = new HuffmanChar(0b11111111111111111100000, 23);
        mapping[148] = new HuffmanChar(0b111111111111111111101110, 24);
        mapping[149] = new HuffmanChar(0b11111111111111111100001, 23);
        mapping[150] = new HuffmanChar(0b11111111111111111100010, 23);
        mapping[151] = new HuffmanChar(0b11111111111111111100011, 23);
        mapping[152] = new HuffmanChar(0b11111111111111111100100, 23);
        mapping[153] = new HuffmanChar(0b111111111111111011100, 21);
        mapping[154] = new HuffmanChar(0b1111111111111111011000, 22);
        mapping[155] = new HuffmanChar(0b11111111111111111100101, 23);
        mapping[156] = new HuffmanChar(0b1111111111111111011001, 22);
        mapping[157] = new HuffmanChar(0b11111111111111111100110, 23);
        mapping[158] = new HuffmanChar(0b11111111111111111100111, 23);
        mapping[159] = new HuffmanChar(0b111111111111111111101111, 24);
        mapping[160] = new HuffmanChar(0b1111111111111111011010, 22);
        mapping[161] = new HuffmanChar(0b111111111111111011101, 21);
        mapping[162] = new HuffmanChar(0b11111111111111101001, 20);
        mapping[163] = new HuffmanChar(0b1111111111111111011011, 22);
        mapping[164] = new HuffmanChar(0b1111111111111111011100, 22);
        mapping[165] = new HuffmanChar(0b11111111111111111101000, 23);
        mapping[166] = new HuffmanChar(0b11111111111111111101001, 23);
        mapping[167] = new HuffmanChar(0b111111111111111011110, 21);
        mapping[168] = new HuffmanChar(0b11111111111111111101010, 23);
        mapping[169] = new HuffmanChar(0b1111111111111111011101, 22);
        mapping[170] = new HuffmanChar(0b1111111111111111011110, 22);
        mapping[171] = new HuffmanChar(0b111111111111111111110000, 24);
        mapping[172] = new HuffmanChar(0b111111111111111011111, 21);
        mapping[173] = new HuffmanChar(0b1111111111111111011111, 22);
        mapping[174] = new HuffmanChar(0b11111111111111111101011, 23);
        mapping[175] = new HuffmanChar(0b11111111111111111101100, 23);
        mapping[176] = new HuffmanChar(0b111111111111111100000, 21);
        mapping[177] = new HuffmanChar(0b111111111111111100001, 21);
        mapping[178] = new HuffmanChar(0b1111111111111111100000, 22);
        mapping[179] = new HuffmanChar(0b111111111111111100010, 21);
        mapping[180] = new HuffmanChar(0b11111111111111111101101, 23);
        mapping[181] = new HuffmanChar(0b1111111111111111100001, 22);
        mapping[182] = new HuffmanChar(0b11111111111111111101110, 23);
        mapping[183] = new HuffmanChar(0b11111111111111111101111, 23);
        mapping[184] = new HuffmanChar(0b11111111111111101010, 20);
        mapping[185] = new HuffmanChar(0b1111111111111111100010, 22);
        mapping[186] = new HuffmanChar(0b1111111111111111100011, 22);
        mapping[187] = new HuffmanChar(0b1111111111111111100100, 22);
        mapping[188] = new HuffmanChar(0b11111111111111111110000, 23);
        mapping[189] = new HuffmanChar(0b1111111111111111100101, 22);
        mapping[190] = new HuffmanChar(0b1111111111111111100110, 22);
        mapping[191] = new HuffmanChar(0b11111111111111111110001, 23);
        mapping[192] = new HuffmanChar(0b11111111111111111111100000, 26);
        mapping[193] = new HuffmanChar(0b11111111111111111111100001, 26);
        mapping[194] = new HuffmanChar(0b11111111111111101011, 20);
        mapping[195] = new HuffmanChar(0b1111111111111110001, 19);
        mapping[196] = new HuffmanChar(0b1111111111111111100111, 22);
        mapping[197] = new HuffmanChar(0b11111111111111111110010, 23);
        mapping[198] = new HuffmanChar(0b1111111111111111101000, 22);
        mapping[199] = new HuffmanChar(0b1111111111111111111101100, 25);
        mapping[200] = new HuffmanChar(0b11111111111111111111100010, 26);
        mapping[201] = new HuffmanChar(0b11111111111111111111100011, 26);
        mapping[202] = new HuffmanChar(0b11111111111111111111100100, 26);
        mapping[203] = new HuffmanChar(0b11111111111111111111101111, 26);
        mapping[204] = new HuffmanChar(0b11111111111111111111101111, 26);
        mapping[205] = new HuffmanChar(0b11111111111111111111100101, 26);
        mapping[206] = new HuffmanChar(0b111111111111111111110001, 24);
        mapping[207] = new HuffmanChar(0b1111111111111111111101101, 25);
        mapping[208] = new HuffmanChar(0b1111111111111110010, 19);
        mapping[209] = new HuffmanChar(0b111111111111111100011, 21);
        mapping[210] = new HuffmanChar(0b11111111111111111111100110, 26);
        mapping[211] = new HuffmanChar(0b11111111111111111111110000, 26);
        mapping[212] = new HuffmanChar(0b11111111111111111111110000, 26);
        mapping[213] = new HuffmanChar(0b11111111111111111111100111, 26);
        mapping[214] = new HuffmanChar(0b11111111111111111111110001, 26);
        mapping[215] = new HuffmanChar(0b111111111111111111110010, 24);
        mapping[216] = new HuffmanChar(0b111111111111111100100, 21);
        mapping[217] = new HuffmanChar(0b111111111111111100101, 21);
        mapping[218] = new HuffmanChar(0b11111111111111111111101000, 26);
        mapping[219] = new HuffmanChar(0b11111111111111111111101001, 26);
        mapping[220] = new HuffmanChar(0b11111111111111111111111111, 26);
        mapping[221] = new HuffmanChar(0b11111111111111111111110001, 26);
        mapping[222] = new HuffmanChar(0b11111111111111111111110010, 26);
        mapping[223] = new HuffmanChar(0b11111111111111111111110010, 26);
        mapping[224] = new HuffmanChar(0b11111111111111101100, 20);
        mapping[225] = new HuffmanChar(0b111111111111111111110011, 24);
        mapping[226] = new HuffmanChar(0b11111111111111101101, 20);
        mapping[227] = new HuffmanChar(0b111111111111111100110, 21);
        mapping[228] = new HuffmanChar(0b1111111111111111101001, 22);
        mapping[229] = new HuffmanChar(0b111111111111111100111, 21);
        mapping[230] = new HuffmanChar(0b111111111111111101000, 21);
        mapping[231] = new HuffmanChar(0b11111111111111111110011, 23);
        mapping[232] = new HuffmanChar(0b1111111111111111101010, 22);
        mapping[233] = new HuffmanChar(0b1111111111111111101011, 22);
        mapping[234] = new HuffmanChar(0b1111111111111111111101110, 25);
        mapping[235] = new HuffmanChar(0b1111111111111111111101111, 25);
        mapping[236] = new HuffmanChar(0b111111111111111111110100, 24);
        mapping[237] = new HuffmanChar(0b111111111111111111110101, 24);
        mapping[238] = new HuffmanChar(0b11111111111111111111101010, 26);
        mapping[239] = new HuffmanChar(0b11111111111111111110100, 23);
        mapping[240] = new HuffmanChar(0b11111111111111111111101011, 26);
        mapping[241] = new HuffmanChar(0b11111111111111111111110011, 26);
        mapping[242] = new HuffmanChar(0b11111111111111111111101100, 26);
        mapping[243] = new HuffmanChar(0b11111111111111111111101101, 26);
        mapping[244] = new HuffmanChar(0b11111111111111111111110011, 26);
        mapping[245] = new HuffmanChar(0b11111111111111111111110100, 26);
        mapping[246] = new HuffmanChar(0b11111111111111111111110100, 26);
        mapping[247] = new HuffmanChar(0b11111111111111111111110101, 26);
        mapping[248] = new HuffmanChar(0b11111111111111111111110101, 26);
        mapping[249] = new HuffmanChar(0b11111111111111111111111111, 26);
        mapping[250] = new HuffmanChar(0b11111111111111111111110110, 26);
        mapping[251] = new HuffmanChar(0b11111111111111111111110110, 26);
        mapping[252] = new HuffmanChar(0b11111111111111111111110111, 26);
        mapping[253] = new HuffmanChar(0b11111111111111111111110111, 26);
        mapping[254] = new HuffmanChar(0b11111111111111111111111000, 26);
        mapping[255] = new HuffmanChar(0b11111111111111111111101110, 26);
        mapping[256] = new HuffmanChar(0b11111111111111111111111111, 26);
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
