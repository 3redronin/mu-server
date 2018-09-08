package io.muserver;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public class BoundariedInputStream extends FilterInputStream {

    private final InputStream source;
    private final byte[] boundary;
    private byte[] buffer;
    private int bufferInd;
    private int bufferLen;
    private boolean isClosed;

    public BoundariedInputStream(InputStream source, String boundary) {
        this(source, boundary.getBytes(StandardCharsets.US_ASCII), new byte[8192], -1, -1);
    }

    private BoundariedInputStream(InputStream source, byte[] boundary, byte[] buffer, int bufferInd, int bufferLen) {
        super(source);
        this.source = source;
        this.boundary = boundary;
        this.buffer = buffer;
        this.bufferInd = bufferInd;
        this.bufferLen = bufferLen;
    }

    public BoundariedInputStream continueNext() {
        return new BoundariedInputStream(source, boundary, buffer, bufferInd, bufferLen);
    }


    @Override
    public int read(byte[] dest, int off, int len) throws IOException {

        if (isClosed) {
            return -1;
        }

        if (bufferInd == -1) {
            bufferInd = 0;
            bufferLen = source.read(buffer);
            if (bufferLen == -1) {
                return -1;
            }
        } else {
            int read = source.read(buffer, bufferLen, buffer.length - bufferLen);
            if (read > 0) {
                bufferLen += read;
            }
        }
        ArrayMatch match = indexOf(buffer, bufferInd, bufferLen, boundary);
        if (match.match()) {
            int length = match.index - bufferInd;
            System.arraycopy(buffer, bufferInd, dest, off, length);
            bufferInd = match.index + boundary.length;
            isClosed = true;
            return length;
        } else if (match.partialMatch()) {
            int length = match.index - bufferInd;
            System.arraycopy(buffer, bufferInd, dest, off, length);
            bufferInd = match.index;
            return length;
        } else {
            int length = bufferLen - bufferInd;
            System.arraycopy(buffer, bufferInd, dest, off, length);
            bufferInd = -1;
            bufferLen = -1;
            return length;
        }


    }

    @Override
    public void close() {
        isClosed = true;
    }

    /**
     * Search the data byte array for the first occurrence of the byte array pattern within given boundaries.
     *
     * @param data
     * @param start   First index in data
     * @param stop    Last index in data so that stop-start = length
     * @param pattern What is being searched. '*' can be used as wildcard for "ANY character"
     * @return
     */
    public static ArrayMatch indexOf(byte[] data, int start, int stop, byte[] pattern) {
        if (data == null || pattern == null) return ArrayMatch.NOPE;

        int[] failure = computeFailure(pattern);

        int j = 0;

        for (int i = start; i < stop; i++) {
            while (j > 0 && pattern[j] != data[i]) {
                j = failure[j - 1];
            }
            if (pattern[j] == data[i]) {
                j++;
            }
            if (j == pattern.length) {
                return new ArrayMatch(i - pattern.length + 1, true);
            }
        }
        if (j > 0) {
            return new ArrayMatch(stop - j, false);
        }
        return ArrayMatch.NOPE;
    }

    static class ArrayMatch {
        final int index;
        final boolean isMatch;

        ArrayMatch(int index, boolean isMatch) {
            this.index = index;
            this.isMatch = isMatch;
        }

        boolean partialMatch() {
            return index > -1 && !isMatch;
        }

        boolean match() {
            return isMatch;
        }

        static final ArrayMatch NOPE = new ArrayMatch(-1, false);

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ArrayMatch that = (ArrayMatch) o;
            return index == that.index && isMatch == that.isMatch;
        }

        @Override
        public int hashCode() {
            return Objects.hash(index, isMatch);
        }

        @Override
        public String toString() {
            return "ArrayMatch{" +
                "index=" + index +
                ", isMatch=" + isMatch +
                '}';
        }
    }

    /**
     * Computes the failure function using a boot-strapping process,
     * where the pattern is matched against itself.
     */
    private static int[] computeFailure(byte[] pattern) {
        int[] failure = new int[pattern.length];

        int j = 0;
        for (int i = 1; i < pattern.length; i++) {
            while (j > 0 && pattern[j] != pattern[i]) {
                j = failure[j - 1];
            }
            if (pattern[j] == pattern[i]) {
                j++;
            }
            failure[i] = j;
        }

        return failure;
    }
}
