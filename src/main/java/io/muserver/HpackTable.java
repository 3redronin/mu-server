package io.muserver;

import org.jetbrains.annotations.NotNull;

import java.util.*;

class HpackTable {

    private static final Map<Integer, FieldLine> staticMap = new TreeMap<>();
    static {
        staticMap.put(1, line(HeaderNames.PSEUDO_AUTHORITY));
        staticMap.put(2, line(HeaderNames.PSEUDO_METHOD, "GET"));
        staticMap.put(3, line(HeaderNames.PSEUDO_METHOD, "POST"));
        staticMap.put(4, line(HeaderNames.PSEUDO_PATH, "/"));
        staticMap.put(5, line(HeaderNames.PSEUDO_PATH, "/index.html"));
        staticMap.put(6, line(HeaderNames.PSEUDO_SCHEME, "http"));
        staticMap.put(7, line(HeaderNames.PSEUDO_SCHEME, "https"));
        staticMap.put(8, line(HeaderNames.PSEUDO_STATUS, "200"));
        staticMap.put(9, line(HeaderNames.PSEUDO_STATUS, "204"));
        staticMap.put(10, line(HeaderNames.PSEUDO_STATUS, "206"));
        staticMap.put(11, line(HeaderNames.PSEUDO_STATUS, "304"));
        staticMap.put(12, line(HeaderNames.PSEUDO_STATUS, "400"));
        staticMap.put(13, line(HeaderNames.PSEUDO_STATUS, "404"));
        staticMap.put(14, line(HeaderNames.PSEUDO_STATUS, "500"));
        staticMap.put(15, line((HeaderString) HeaderNames.ACCEPT_CHARSET));
        staticMap.put(16, line((HeaderString) HeaderNames.ACCEPT_ENCODING, "gzip, deflate"));
        staticMap.put(17, line((HeaderString) HeaderNames.ACCEPT_LANGUAGE));
        staticMap.put(18, line((HeaderString) HeaderNames.ACCEPT_RANGES));
        staticMap.put(19, line((HeaderString) HeaderNames.ACCEPT));
        staticMap.put(20, line((HeaderString) HeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN));
        staticMap.put(21, line((HeaderString) HeaderNames.AGE));
        staticMap.put(22, line((HeaderString) HeaderNames.ALLOW));
        staticMap.put(23, line((HeaderString) HeaderNames.AUTHORIZATION));
        staticMap.put(24, line((HeaderString) HeaderNames.CACHE_CONTROL));
        staticMap.put(25, line((HeaderString) HeaderNames.CONTENT_DISPOSITION));
        staticMap.put(26, line((HeaderString) HeaderNames.CONTENT_ENCODING));
        staticMap.put(27, line((HeaderString) HeaderNames.CONTENT_LANGUAGE));
        staticMap.put(28, line((HeaderString) HeaderNames.CONTENT_LENGTH));
        staticMap.put(29, line((HeaderString) HeaderNames.CONTENT_LOCATION));
        staticMap.put(30, line((HeaderString) HeaderNames.CONTENT_RANGE));
        staticMap.put(31, line((HeaderString) HeaderNames.CONTENT_TYPE));
        staticMap.put(32, line((HeaderString) HeaderNames.COOKIE));
        staticMap.put(33, line((HeaderString) HeaderNames.DATE));
        staticMap.put(34, line((HeaderString) HeaderNames.ETAG));
        staticMap.put(35, line((HeaderString) HeaderNames.EXPECT));
        staticMap.put(36, line((HeaderString) HeaderNames.EXPIRES));
        staticMap.put(37, line((HeaderString) HeaderNames.FROM));
        staticMap.put(38, line((HeaderString) HeaderNames.HOST));
        staticMap.put(39, line((HeaderString) HeaderNames.IF_MATCH));
        staticMap.put(40, line((HeaderString) HeaderNames.IF_MODIFIED_SINCE));
        staticMap.put(41, line((HeaderString) HeaderNames.IF_NONE_MATCH));
        staticMap.put(42, line((HeaderString) HeaderNames.IF_RANGE));
        staticMap.put(43, line((HeaderString) HeaderNames.IF_UNMODIFIED_SINCE));
        staticMap.put(44, line((HeaderString) HeaderNames.LAST_MODIFIED));
        staticMap.put(45, line((HeaderString) HeaderNames.LINK));
        staticMap.put(46, line((HeaderString) HeaderNames.LOCATION));
        staticMap.put(47, line((HeaderString) HeaderNames.MAX_FORWARDS));
        staticMap.put(48, line((HeaderString) HeaderNames.PROXY_AUTHENTICATE));
        staticMap.put(49, line((HeaderString) HeaderNames.PROXY_AUTHORIZATION));
        staticMap.put(50, line((HeaderString) HeaderNames.RANGE));
        staticMap.put(51, line((HeaderString) HeaderNames.REFERER));
        staticMap.put(52, line((HeaderString) HeaderNames.REFRESH));
        staticMap.put(53, line((HeaderString) HeaderNames.RETRY_AFTER));
        staticMap.put(54, line((HeaderString) HeaderNames.SERVER));
        staticMap.put(55, line((HeaderString) HeaderNames.SET_COOKIE));
        staticMap.put(56, line((HeaderString) HeaderNames.STRICT_TRANSPORT_SECURITY));
        staticMap.put(57, line((HeaderString) HeaderNames.TRANSFER_ENCODING));
        staticMap.put(58, line((HeaderString) HeaderNames.USER_AGENT));
        staticMap.put(59, line((HeaderString) HeaderNames.VARY));
        staticMap.put(60, line((HeaderString) HeaderNames.VIA));
        staticMap.put(61, line((HeaderString) HeaderNames.WWW_AUTHENTICATE));
    }

    int size() {
        return staticMap.size() + dynamicQueue.size();
    }

    private final Set<FieldLine> neverIndex = new HashSet<>();

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
        if (code <= staticMap.size()) return staticMap.get(code);
        int dIndex = code - staticMap.size() - 1;
        var dynamicVal = dynamicQueue.get(dIndex);
        if (dynamicVal == null) {
            throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR, "Invalid dynamic code");
        }
        return dynamicVal;
    }

    int codeFor(FieldLine line) {
        for (Map.Entry<Integer, FieldLine> entry : staticMap.entrySet()) {
            if (entry.getValue().equals(line)) return entry.getKey();
        }
        int i = 0;
        for (FieldLine cachedLine : dynamicQueue) {
            if (cachedLine.equals(line)) return i + staticMap.size() + 1 /* because there is no 0 */;
            i++;
        }
        return -1;
    }

    int codeFor(HeaderString name) {
        for (Map.Entry<Integer, FieldLine> entry : staticMap.entrySet()) {
            if (entry.getValue().name().equals(name)) return entry.getKey();
        }
        int i = 0;
        for (FieldLine cachedLine : dynamicQueue) {
            if (cachedLine.name().equals(name)) {
                return i + staticMap.size() + 1 /* because there is no 0 */;
            }
            i++;
        }
        return -1;
    }

    public void indexField(FieldLine line) {
        dynamicQueue.add(0, line);
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
