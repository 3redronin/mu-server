package io.muserver;

import java.io.IOException;
import java.text.ParseException;

class ParseUtils {

    static final byte SP = 32;
    static final byte CR = 13;
    static final byte LF = 10;
    static final byte HTAB = 9;
    static final byte A = 65;
    static final byte A_LOWER = 97;
    static final byte F = 70;
    static final byte F_LOWER = 102;
    static final byte Z = 90;
    static final byte COLON = 58;
    static final byte SEMICOLON = 59;
    static final byte ZERO = 48;
    static final byte NINE = 57;
    static final byte[] COLON_SP = new byte[] { COLON, SP };
    static final byte[] CRLF = new byte[] { CR, LF };

    static boolean isTChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9' || c == '!' ||
            c == '#' || c == '$' || c == '%' || c == '&' || c == '\'' || c == '*' || c == '+' ||
            c == '-' || c == '.' || c == '^' || c == '_' || c == '`' || c == '|' || c == '~');
    }

    static boolean isTChar(byte b) {
        return (b >= (byte)'a' && b <= (byte)'z') || (b >= (byte)'A' && b <= (byte)'Z') || (b >= (byte)'0' && b <= (byte)'9' || b == (byte)'!' ||
            b == (byte)'#' || b == (byte)'$' || b == (byte)'%' || b == (byte)'&' || b == (byte)'\'' || b == (byte)'*' || b == (byte)'+' ||
            b == (byte)'-' || b == (byte)'.' || b == (byte)'^' || b == (byte)'_' || b == (byte)'`' || b == (byte)'|' || b == (byte)'~');
    }


    static boolean isVChar(char c) {
        return c >= 0x21 && c <= 0x7E;
    }
    static boolean isVChar(byte c) {
        return c >= 0x21 && c <= 0x7E;
    }

    static boolean isOWS(char c) {
        return c == ' ' || c == '\t';
    }
    static boolean isOWS(byte c) {
        return c == ' ' || c == '\t';
    }

    static String quoteIfNeeded(String value) {
        boolean needsQuoting = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!isTChar(c)) {
                needsQuoting = true;
                break;
            }
        }
        return needsQuoting ? '"' + value.replace("\"", "\\\"") + '"' : value;
    }
}

enum HttpMessageType { REQUEST, RESPONSE }

interface Http1ConnectionMsg {}

class MessageBodyBit implements Http1ConnectionMsg {
    static final MessageBodyBit EndOfBodyBit = new MessageBodyBit(new byte[0], 0, 0, true);
    static final MessageBodyBit EOFMsg = new MessageBodyBit(new byte[0], 0, 0, false);
    private final byte[] bytes;
    private final int offset;
    private final int length;
    private final boolean isLast;
    MessageBodyBit(byte[] bytes, int offset, int length, boolean isLast) {
        this.bytes = bytes;
        this.offset = offset;
        this.length = length;
        this.isLast = isLast;
    }

    public byte[] bytes() {
        return bytes;
    }

    public int offset() {
        return offset;
    }

    public int length() {
        return length;
    }

    public boolean isLast() {
        return isLast;
    }
}

interface Http1MessageReader {
    Http1ConnectionMsg readNext() throws IOException, ParseException;
}
