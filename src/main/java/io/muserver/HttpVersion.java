package io.muserver;

import java.nio.charset.StandardCharsets;

/**
 * An HTTP protocol version
 */
public enum HttpVersion {

    /**
     * HTTP/1.0
     */
    HTTP_1_0("HTTP/1.0"),

    /**
     * HTTP/1.1
     */
    HTTP_1_1("HTTP/1.1");

    private final String version;

    HttpVersion(String version) {
        this.version = version;
    }

    /**
     * @return The version as a string in the way it appears in the HTTP Protocol, for example <code>HTTP/1.0</code>
     */
    public String version() {
        return version;
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
