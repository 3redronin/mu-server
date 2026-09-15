package io.muserver;

/** Validates one text message incrementally, without buffering or decoding its characters. */
final class WebsocketUtf8Validator {
    private int remaining;
    private int minimum = 0x80;
    private int maximum = 0xBF;

    void reset() {
        remaining = 0;
        minimum = 0x80;
        maximum = 0xBF;
    }

    boolean accept(int octet) {
        if (remaining > 0) {
            if (octet < minimum || octet > maximum) return false;
            remaining--;
            minimum = 0x80;
            maximum = 0xBF;
            return true;
        }
        if (octet <= 0x7F) return true;
        if (octet >= 0xC2 && octet <= 0xDF) {
            remaining = 1;
        } else if (octet >= 0xE0 && octet <= 0xEF) {
            remaining = 2;
            if (octet == 0xE0) minimum = 0xA0; // Exclude overlong encodings.
            if (octet == 0xED) maximum = 0x9F; // Exclude UTF-16 surrogates.
        } else if (octet >= 0xF0 && octet <= 0xF4) {
            remaining = 3;
            if (octet == 0xF0) minimum = 0x90; // Exclude overlong encodings.
            if (octet == 0xF4) maximum = 0x8F; // Maximum code point is U+10FFFF.
        } else {
            return false;
        }
        return true;
    }

    boolean isComplete() {
        return remaining == 0;
    }
}
