package io.muserver;

import java.nio.charset.StandardCharsets;

/**
 * An HTTP Method
 */
public enum Method {

    /**
     * The GET HTTP method
     */
    GET,
    /**
     * The POST HTTP method
     */
    POST,
    /**
     * The HEAD HTTP method
     */
    HEAD,
    /**
     * The OPTIONS HTTP method
     */
    OPTIONS,
    /**
     * The PUT HTTP method
     */
    PUT,
    /**
     * The DELETE HTTP method
     */
    DELETE,
    /**
     * The TRACE HTTP method
     */
    TRACE,
    /**
     * The CONNECT HTTP method
     */
    CONNECT,
    /**
     * The PATCH HTTP method
     */
    PATCH;

    /**
     * Specifies if this method is {@link #HEAD}
     * @return <code>true</code> if this is a <code>HEAD</code> request
     */
    public boolean isHead() {
        return this == HEAD;
    }

    /**
     * Specifies if this method is {@link #GET} {@link #HEAD}
     * @return <code>true</code> if this is a <code>HEAD</code> request
     */
    public boolean isGetOrHead() {
        return this == GET || this == HEAD;
    }

    byte[] headerBytes() {
        return this.name().getBytes(StandardCharsets.US_ASCII);
    }
}
