package io.muserver;

import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.stream.IntStream;

class HeaderString implements CharSequence {

    private final String s;
    final byte[] bytes;

    private HeaderString(CharSequence value) {
        this.s = value.toString();
        this.bytes = s.getBytes(StandardCharsets.US_ASCII);
        if (s.length() != bytes.length) {
            throw new IllegalArgumentException("Non ascii characters");
        }
    }

    public static HeaderString valueOf(String value) {
        return new HeaderString(value);
    }

    @Override
    public int length() {
        return s.length();
    }

    @Override
    public char charAt(int index) {
        return s.charAt(index);
    }

    @NotNull
    @Override
    public CharSequence subSequence(int start, int end) {
        return s.subSequence(start, end);
    }

    @NotNull
    @Override
    public IntStream chars() {
        return s.chars();
    }

    @NotNull
    @Override
    public IntStream codePoints() {
        return s.codePoints();
    }

    @Override
    public int hashCode() {
        return s.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj instanceof HeaderString) {
            return ((HeaderString) obj).s.equals(s);
        }
        return false;
    }

    @Override
    @NotNull
    public String toString() {
        return s;
    }
}
