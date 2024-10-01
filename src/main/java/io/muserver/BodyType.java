package io.muserver;

/**
 * The declared type of request or response body
 */
public enum BodyType {

    /**
     * The size of the body is known
     */
    FIXED_SIZE(true),

    /**
     * The body is sent with `transfer-encoding: chunked`
     */
    CHUNKED(false),

    /**
     * There is a body, but the size is not known and it is not chunked, so the body are all bytes until the connection closes
     */
    UNSPECIFIED(false),

    /**
     * There is no body
     */
    NONE(true);

    private final boolean sizeKnown;

    BodyType(boolean sizeKnown) {
        this.sizeKnown = sizeKnown;
    }

    /**
     * @return <code>true</code> if this is a fixed size or empty body; otherwise <code>false</code>
     */
    public boolean sizeKnown() {
        return sizeKnown;
    }
}

