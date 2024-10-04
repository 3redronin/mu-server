package io.muserver;

import java.nio.charset.StandardCharsets;

/**
 * An HTTP protocol version
 */
public enum HttpVersion {

    /**
     * HTTP/1.0
     */
    HTTP_1_0("HTTP/1.0", 1),

    /**
     * HTTP/1.1
     */
    HTTP_1_1("HTTP/1.1", 1),

    /**
     * HTTP/2
     */
    HTTP_2("HTTP/2", 2);

    private final String version;
    private final int majorVersion;

    HttpVersion(String version, int majorVersion) {
        this.version = version;
        this.majorVersion = majorVersion;
    }

    /**
     * @return The version as a string in the way it appears in the HTTP Protocol, for example <code>HTTP/1.0</code>
     */
    public String version() {
        return version;
    }

    /**
     * The major version, e.g. <code>1</code> for both {@link #HTTP_1_1} and {@link #HTTP_1_0}
     * @return the major version of this HTTP version
     */
    public int majorVersion() {
        return majorVersion;
    }

    /**
     * Converts a string into a protocol version.
     * <p>For example, <code>&quot;HTTP/1.1&quot;</code> will get converted to {@link #HTTP_1_1}</p>
     * @param value A value such as <code>HTTP/1.1</code>
     * @return The protocol version, or <code>null</code> if it is not a protocol version recognised by mu-server
     */
    public static HttpVersion fromVersion(String value) {
        switch (value) {
            case "HTTP/1.1": return HTTP_1_1;
            case "HTTP/1.0": return HTTP_1_0;
            default: return null;
        }
    }

    byte[] headerBytes() {
        return version.getBytes(StandardCharsets.US_ASCII);
    }


    @Override
    public String toString() {
        return version;
    }
}
