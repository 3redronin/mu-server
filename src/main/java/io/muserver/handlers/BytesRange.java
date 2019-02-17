package io.muserver.handlers;

import java.util.ArrayList;
import java.util.List;

import static java.util.Collections.emptyList;

class BytesRange {
    final long from;
    final long to;
    final long total;

    private BytesRange(long from, long to, long total) {
        this.from = Math.max(0, from);
        this.to = to;
        this.total = total;
        if (from >= to || to >= total) {
            throw new IllegalArgumentException("The range " + from + " and " + to + " is invalid for length " + total);
        }
    }
    static List<BytesRange> parse(long totalBytes, String value) {
        if (value == null || value.trim().isEmpty() || !value.startsWith("bytes=")) {
            return emptyList();
        }

        String[] split = value.substring("bytes=".length()).split(",");

        List<BytesRange> ranges = new ArrayList<>();
        for (String s : split) {
            String[] bits = s.split("-", 2);
            if (bits.length != 2) {
                throw new IllegalArgumentException("Invalid range");
            }
            long from = getRangeValue(bits[0]);
            long to = getRangeValue(bits[1]);
            if (from == -1 && to == -1) {
                throw new IllegalArgumentException("Invalid range value: " + s);
            }
            if (from == -1) {
                from = totalBytes - to;
                to = totalBytes - 1;
            } else if (to == -1) {
                to = totalBytes - 1;
            }
            from = Math.max(0, from);
            to = Math.min(to, totalBytes - 1);
            ranges.add(new BytesRange(from, to, totalBytes));
        }
        return ranges;
    }

    private static long getRangeValue(String bit) {
        bit = bit.trim();
        if (bit.isEmpty()) {
            return -1;
        }
        try {
            return Long.parseLong(bit);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public String toString() {
        return "bytes " + from + "-" + to + "/" + total;
    }

    public long length() {
        return to - from + 1;
    }
}

