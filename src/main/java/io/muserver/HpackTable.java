package io.muserver;

import org.jetbrains.annotations.NotNull;

import java.util.*;

class HpackTable {

    private static final FieldLine[] staticMap;

    static {
        staticMap = new FieldLine[]{
            null,
            line(HeaderNames.PSEUDO_AUTHORITY),
            line(HeaderNames.PSEUDO_METHOD, "GET"),
            line(HeaderNames.PSEUDO_METHOD, "POST"),
            line(HeaderNames.PSEUDO_PATH, "/"),
            line(HeaderNames.PSEUDO_PATH, "/index.html"),
            line(HeaderNames.PSEUDO_SCHEME, "http"),
            line(HeaderNames.PSEUDO_SCHEME, "https"),
            line(HeaderNames.PSEUDO_STATUS, "200"),
            line(HeaderNames.PSEUDO_STATUS, "204"),
            line(HeaderNames.PSEUDO_STATUS, "206"),
            line(HeaderNames.PSEUDO_STATUS, "304"),
            line(HeaderNames.PSEUDO_STATUS, "400"),
            line(HeaderNames.PSEUDO_STATUS, "404"),
            line(HeaderNames.PSEUDO_STATUS, "500"),
            line((HeaderString) HeaderNames.ACCEPT_CHARSET),
            line((HeaderString) HeaderNames.ACCEPT_ENCODING, "gzip, deflate"),
            line((HeaderString) HeaderNames.ACCEPT_LANGUAGE),
            line((HeaderString) HeaderNames.ACCEPT_RANGES),
            line((HeaderString) HeaderNames.ACCEPT),
            line((HeaderString) HeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN),
            line((HeaderString) HeaderNames.AGE),
            line((HeaderString) HeaderNames.ALLOW),
            line((HeaderString) HeaderNames.AUTHORIZATION),
            line((HeaderString) HeaderNames.CACHE_CONTROL),
            line((HeaderString) HeaderNames.CONTENT_DISPOSITION),
            line((HeaderString) HeaderNames.CONTENT_ENCODING),
            line((HeaderString) HeaderNames.CONTENT_LANGUAGE),
            line((HeaderString) HeaderNames.CONTENT_LENGTH),
            line((HeaderString) HeaderNames.CONTENT_LOCATION),
            line((HeaderString) HeaderNames.CONTENT_RANGE),
            line((HeaderString) HeaderNames.CONTENT_TYPE),
            line((HeaderString) HeaderNames.COOKIE),
            line((HeaderString) HeaderNames.DATE),
            line((HeaderString) HeaderNames.ETAG),
            line((HeaderString) HeaderNames.EXPECT),
            line((HeaderString) HeaderNames.EXPIRES),
            line((HeaderString) HeaderNames.FROM),
            line((HeaderString) HeaderNames.HOST),
            line((HeaderString) HeaderNames.IF_MATCH),
            line((HeaderString) HeaderNames.IF_MODIFIED_SINCE),
            line((HeaderString) HeaderNames.IF_NONE_MATCH),
            line((HeaderString) HeaderNames.IF_RANGE),
            line((HeaderString) HeaderNames.IF_UNMODIFIED_SINCE),
            line((HeaderString) HeaderNames.LAST_MODIFIED),
            line((HeaderString) HeaderNames.LINK),
            line((HeaderString) HeaderNames.LOCATION),
            line((HeaderString) HeaderNames.MAX_FORWARDS),
            line((HeaderString) HeaderNames.PROXY_AUTHENTICATE),
            line((HeaderString) HeaderNames.PROXY_AUTHORIZATION),
            line((HeaderString) HeaderNames.RANGE),
            line((HeaderString) HeaderNames.REFERER),
            line((HeaderString) HeaderNames.REFRESH),
            line((HeaderString) HeaderNames.RETRY_AFTER),
            line((HeaderString) HeaderNames.SERVER),
            line((HeaderString) HeaderNames.SET_COOKIE),
            line((HeaderString) HeaderNames.STRICT_TRANSPORT_SECURITY),
            line((HeaderString) HeaderNames.TRANSFER_ENCODING),
            line((HeaderString) HeaderNames.USER_AGENT),
            line((HeaderString) HeaderNames.VARY),
            line((HeaderString) HeaderNames.VIA),
            line((HeaderString) HeaderNames.WWW_AUTHENTICATE)
        };

    }

    /**
     * The maximum size of the dynamic table
     */
    private int maxSize;

    private final Set<FieldLine> neverIndex = new HashSet<>();

    HpackTable(int maxSize) {
        this.maxSize = maxSize;
    }

    void changeMaxSize(int newSize) {
        maxSize = newSize;
        trimSize();
    }

    private void trimSize() {
        while (dynamicTableSizeInBytes() > maxSize) {
            dynamicQueue.removeLast();
        }
    }

    @NotNull
    private static FieldLine line(HeaderString name) {
        return new FieldLine(name, HeaderString.EMPTY_VALUE);
    }

    @NotNull
    private static FieldLine line(HeaderString name, String value) {
        return new FieldLine(name, HeaderString.valueOf(value, HeaderString.Type.VALUE));
    }

    private final LinkedList<FieldLine> dynamicQueue = new LinkedList<>();

    FieldLine getValue(int code) throws Http2Exception {
        if (code <= 0) throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR, "Invalid code");
        if (code < staticMap.length) return staticMap[code];
        int dIndex = code - staticMap.length;
        if (dIndex >= dynamicQueue.size()) {
            throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR, "Invalid dynamic code");
        }
        return dynamicQueue.get(dIndex);
    }

    int codeFor(FieldLine line) {
        for (int i = 1; i < staticMap.length; i++) {
            var staticLine = staticMap[i];
            if (staticLine.equals(line)) return i;
        }
        int i = 0;
        for (FieldLine cachedLine : dynamicQueue) {
            if (cachedLine.equals(line)) return i + staticMap.length;
            i++;
        }
        return -1;
    }

    int codeFor(HeaderString name) {
        for (int i = 1; i < staticMap.length; i++) {
            var staticLine = staticMap[i];
            if (staticLine.name().equals(name)) return i;
        }
        int i = 0;
        for (FieldLine cachedLine : dynamicQueue) {
            if (cachedLine.name().equals(name)) {
                return i + staticMap.length;
            }
            i++;
        }
        return -1;
    }

    public void indexField(FieldLine line) {
        dynamicQueue.add(0, line);
        trimSize();
    }

    void neverIndex(FieldLine line) {
        System.out.println("Never index " + line);
        neverIndex.add(line);
    }

    boolean isNeverIndex(FieldLine line) {
        return neverIndex.contains(line);
    }

    /**
     * From rfc7541 4.1:
     *
     * <p>The size of an entry is the sum of its name's length in octets (as
     * defined in Section 5.2), its value's length in octets, and 32.</p>
     *
     * <p>The size of an entry is calculated using the length of its name and
     * value without any Huffman encoding applied.</p>
     *
     * @return size in bytes
     */
    public int dynamicTableSizeInBytes() {
        int size = 0;
        for (FieldLine line : dynamicQueue) {
            size += line.length() + 32;
        }
        return size;
    }
}
