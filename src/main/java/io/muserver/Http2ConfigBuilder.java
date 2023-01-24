package io.muserver;

/**
 * Configuration builder for enabling HTTP2 by passing the config to {@link MuServerBuilder#withHttp2Config(Http2ConfigBuilder)}
 */
public class Http2ConfigBuilder {

    private boolean enabled = false;

    /**
     * Specifies whether to enable HTTP2 or not.
     * @param enabled <code>true</code> to enable; <code>false</code> to disable.
     * @return This builder
     */
    public Http2ConfigBuilder enabled(boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    /**
     * @return The current value of this property
     */
    public boolean enabled() {
        return enabled;
    }

    /**
     * Creates the HTTP2 settings object
     * @return A new Http2Config object
     */
    public Http2Config build() {
        return new Http2Config(enabled);
    }

    /**
     * Creates a new config where HTTP2 is disabled
     * @return A new builder
     */
    public static Http2ConfigBuilder http2Config() {
        return new Http2ConfigBuilder();
    }

    /**
     * Creates a new config where HTTP2 is enabled
     * @return A new builder
     */
    public static Http2ConfigBuilder http2Enabled() {
        return new Http2ConfigBuilder().enabled(true);
    }

    /**
     * <p>Creates a new config where HTTP2 is enabled if supported by the Java version.</p>
     * <p>The current logic may not always return the correct results. It does not actually test for availability
     * and instead tries to detect the Java version, and enables HTTP2 for Java 9 or later.</p>
     * @return A new builder
     */
    public static Http2ConfigBuilder http2EnabledIfAvailable() {
        boolean isJava8 = "1.8".equals(System.getProperty("java.specification.version"));
        return new Http2ConfigBuilder().enabled(!isJava8);
    }

}
