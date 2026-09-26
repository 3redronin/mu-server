package io.muserver;

import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Locale;
import java.util.stream.IntStream;

class HeaderString implements CharSequence {

    enum Type {
        HEADER, VALUE
    }

    private final String s;
    final byte[] bytes;

    HeaderString(CharSequence value) {
        this.s = value.toString();
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 0xFF) {
                throw new IllegalArgumentException("Header characters must fit in one octet");
            }
        }
        this.bytes = s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private HeaderString(String s, byte[] bytes) {
        this.s = s;
        this.bytes = bytes;
    }

    static HeaderString valueOf(Object value, Type type) {
        if (type == Type.HEADER && value instanceof ValidatedHeaderName) {
            return (ValidatedHeaderName) value;
        }
        if (type == Type.VALUE && value instanceof ValidatedHeaderValue) {
            return (ValidatedHeaderValue) value;
        }
        String s = value instanceof Date ? Mutils.toHttpDate((Date)value) : value.toString();
        if (s.isEmpty()) {
            if (type == Type.HEADER) throw new IllegalArgumentException("Empty header names not allowed");
            return ValidatedHeaderValue.EMPTY_VALUE;
        }
        if (type == Type.HEADER) {
            var builtIn = HeaderNames.findBuiltIn(s);
            if (builtIn != null) {
                return builtIn;
            }
            // Known pseudo-fields are used internally; all other names are ASCII tokens.
            int start = s.charAt(0) == ':' ? 1 : 0;
            for (int i = start; i < s.length(); i++) {
                if (!ParseUtils.isTChar(s.charAt(i))) {
                    throw new IllegalArgumentException("Invalid HTTP header name");
                }
            }
            String original = s;
            s = s.toLowerCase(Locale.ROOT);
            if (!s.equals(original)) {
                builtIn = HeaderNames.findBuiltIn(s);
                if (builtIn != null) {
                    return builtIn;
                }
            }
            if (start != 0) throw new IllegalArgumentException("Invalid HTTP header name");
        } else {
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c > 0xFF || (c != '\t' && (c < 0x20 || c == 0x7F))) {
                    throw new IllegalArgumentException("Invalid HTTP header value");
                }
            }
            int start = 0;
            int end = s.length();
            while (start < end && ParseUtils.isOWS(s.charAt(start))) {
                start++;
            }
            while (end > start && ParseUtils.isOWS(s.charAt(end - 1))) {
                end--;
            }
            if (start == end) return ValidatedHeaderValue.EMPTY_VALUE;
            s = s.substring(start, end);
        }
        return type == Type.HEADER ? ValidatedHeaderName.custom(s) : ValidatedHeaderValue.normalized(s);
    }
    static HeaderString valueOf(byte[] octets, Type type) {
        if (octets.length == 0) return ValidatedHeaderValue.EMPTY_VALUE;
        var s = new String(octets, StandardCharsets.ISO_8859_1);
        if (type != Type.VALUE) {
            var builtIn = HeaderNames.findBuiltIn(s);
            if (builtIn != null) {
                return builtIn;
            }
        }
        return new HeaderString(s, octets);
    }

    @Override
    public int length() {
        return bytes.length;
    }

    @Override
    public char charAt(int index) {
        return (char)(bytes[index] & 0xFF);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        return s.subSequence(start, end);
    }

    @Override
    public IntStream chars() {
        return s.chars();
    }

    @Override
    public IntStream codePoints() {
        return s.codePoints();
    }

    @Override
    public int hashCode() {
        return s.hashCode();
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj instanceof HeaderString) {
            HeaderString hs = (HeaderString) obj;
            return hs.s.equals(s);
        }
        return false;
    }

    @Override
    public String toString() {
        return s;
    }

    public boolean contentEquals(@Nullable CharSequence other) {
        if (other == null) return false;
        if (other instanceof HeaderString) {
            return equals(other);
        } else {
            return this.s.contentEquals(other);
        }
    }

    public boolean contentEquals(@Nullable CharSequence other, boolean ignoreCase) {
        if (other == null) return false;
        if (other instanceof HeaderString) {
            HeaderString hs = (HeaderString) other;
            if (!ignoreCase) {
                return this.equals(hs);
            }
        }
        if (ignoreCase) {
            return this.s.equalsIgnoreCase(other.toString());
        } else {
            return this.s.contentEquals(other);
        }
    }

    boolean containsChar(byte c) {
        for (byte b : bytes) {
            if (b == c) return true;
        }
        return false;
    }

}
