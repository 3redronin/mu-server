package io.muserver;

/**
 * Configuration builder for enabling HTTP2 by passing the config to {@link MuServerBuilder#withHttp2Config(Http2ConfigBuilder)}
 */
public class Http2ConfigBuilder {

    private boolean enabled = false;
    private int maxConcurrentStreams = 200;

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
     * Gets the maximum number concurrent streams (HTTP requests) on a single HTTP2 connection.
     *
     * @return the maximum number of concurrent streams allowed. Default is 200.
     */
    public int maxConcurrentStreams() {
        return maxConcurrentStreams;
    }

    /**
     * Sets the maximum number of concurrent streams allowed per HTTP2 connection.
     *
     * <p>The default is 200.</p>
     *
     * <p>This setting controls the maximum number of concurrent requests
     * that can be initiated by the client for a single HTTP2 connection. A higher value can improve
     * concurrency but may also lead to increased resource consumption.</p>
     *
     * <p>Limits:</p>
     * <ul>
     * <li>Minimum: 0 (note: a value of 0 is technically allowed but will prevent clients from sending requests)</li>
     * <li>No specified maximum</li>
     * </ul>
     *
     * @param maxConcurrentStreams the initial maximum number of concurrent streams.
     * @return this builder
     * @throws IllegalArgumentException if the number of streams is less than 0.
     */
    public Http2ConfigBuilder withMaxConcurrentStreams(int maxConcurrentStreams) {
        if (maxConcurrentStreams < 0) {
            throw new IllegalArgumentException("Maximum concurrent streams must be non-negative.");
        }
        this.maxConcurrentStreams = maxConcurrentStreams;
        return this;
    }

    /**
     * Creates the HTTP2 settings object
     * @return A new Http2Config object
     */
    public Http2Config build() {
        return new Http2Config(enabled, maxConcurrentStreams);
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
