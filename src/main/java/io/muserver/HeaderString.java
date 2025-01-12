package io.muserver;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.stream.IntStream;

@NullMarked
class HeaderString implements CharSequence {

    enum Type {
        HEADER, VALUE
    }

    private final String s;
    final byte[] bytes;

    HeaderString(CharSequence value) {
        this.s = value.toString();
        this.bytes = s.getBytes(StandardCharsets.US_ASCII);
        if (s.length() != bytes.length) {
            throw new IllegalArgumentException("Non ascii characters");
        }
    }

    private HeaderString(String s, byte[] bytes) {
        this.s = s;
        this.bytes = bytes;
    }

    static HeaderString valueOf(Object value, Type type) {
        if (value instanceof HeaderString) {
            return (HeaderString) value;
        }
        CharSequence s = value instanceof CharSequence ? (CharSequence) value : value.toString();
        if (s.length() == 0) {
            if (type == Type.HEADER) throw new IllegalArgumentException("Empty header names not allowed");
            return EMPTY_VALUE;
        }
        if (type == Type.HEADER) {
            var builtIn = HeaderNames.findBuiltIn(s);
            if (builtIn != null) {
                return builtIn;
            }
            if (s instanceof String) {
                s = ((String) s).toLowerCase();
            }
        }
        return new HeaderString(s);
    }
    static HeaderString valueOf(byte[] ascii, Type type) {
        if (ascii.length == 0) return EMPTY_VALUE;
        var s = new String(ascii, StandardCharsets.US_ASCII);
        if (type != Type.VALUE) {
            var builtIn = HeaderNames.findBuiltIn(s);
            if (builtIn != null) {
                return builtIn;
            }
        }
        return new HeaderString(s, ascii);
    }

    @Override
    public int length() {
        return bytes.length;
    }

    @Override
    public char charAt(int index) {
        return (char)bytes[index];
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

    static HeaderString EMPTY_VALUE = new HeaderString("");
}
