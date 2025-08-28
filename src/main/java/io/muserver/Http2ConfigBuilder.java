package io.muserver;

import java.util.Objects;

/**
 * Configuration builder for HTTP2 settings.
 *
 * <p>Note that by default, HTTP2 is enabled with recommended settings, so this class generally does not
 * need to be used unless very specific HTTP2 requirements are needed, or if HTTP2 should be disabled.</p>
 *
 * <p>Note that any fields prefixed with &quot;initial&quot; are just that: the initial settings of a connection.
 * These settings may change during the life of an HTTP2 connection.</p>
 */
public class Http2ConfigBuilder {

    private boolean enabled = false;
    private int maxHeaderTableSize = 4096;
    private int maxConcurrentStreams = 200;
    private int initialWindowSize = 65535;
    private int maxFrameSize = 16384;
    private int maxHeaderListSize = -1;

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
     * @return <code>true</code> if HTTP2 is enabled for this server; otherwise <code>false</code>.
     */
    public boolean enabled() {
        return enabled;
    }

    /**
     * Controls whether HTTP2 is enabled on this server or not. Defaults to <code>true</code>.
     * @param enabled <code>true</code> to enable HTTP2
     * @return This builder
     */
    public Http2ConfigBuilder withEnabled(boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    /**
     * Gets the maximum header table size.
     *
     * @return the maximum header table size in bytes. Default is 4096 bytes.
     */
    public int maxHeaderTableSize() {
        return maxHeaderTableSize;
    }

    /**
     * Sets the maximum header table size.
     *
     * <p>The default value is 4096 bytes.</p>
     *
     * <p>The header table size determines the maximum size of the header
     * table used for compression. A larger size can improve compression
     * efficiency but may increase memory usage.</p>
     *
     * <p>Limits:</p>
     * <ul>
     * <li>Minimum: 0</li>
     * <li>Maximum: 2^14 (16384)</li>
     * </ul>
     *
     * @param maxHeaderTableSize the maximum header table size in bytes.
     * @return this builder
     * @throws IllegalArgumentException if the size is less than 0 or greater than 16384.
     */
    public Http2ConfigBuilder withMaxHeaderTableSize(int maxHeaderTableSize) {
        if (maxHeaderTableSize < 0 || maxHeaderTableSize > 16384) {
            throw new IllegalArgumentException("Header table size must be between 0 and 16384 bytes.");
        }
        this.maxHeaderTableSize = maxHeaderTableSize;
        return this;
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
     * Gets the initial window size.
     *
     * @return the initial window size in bytes. Default is 65535 bytes.
     */
    public int initialWindowSize() {
        return initialWindowSize;
    }

    /**
     * Sets the initial flow control window size for streams.
     *
     * <p>The default is 65535 bytes.</p>
     *
     * <p>The initial window size controls the flow control for streams.
     * A larger window size can improve throughput but may increase memory usage
     * and lead to higher latency.</p>
     *
     * <p>Limits:</p>
     * <ul>
     * <li>Minimum: 0 (which would prevent clients from sending data)</li>
     * <li>Maximum: No specific maximum</li>
     * </ul>
     *
     * @param initialWindowSize the initial window size in bytes.
     * @return this {@code Http2ConfigBuilder} instance for method chaining.
     * @throws IllegalArgumentException if the size is less than 0 or greater than 2147483647.
     */
    public Http2ConfigBuilder withInitialWindowSize(int initialWindowSize) {
        if (initialWindowSize < 0) {
            throw new IllegalArgumentException("Window size is negative");
        }
        this.initialWindowSize = initialWindowSize;
        return this;
    }

    /**
     * Gets the maximum frame size the server is willing to receive from clients.
     *
     * @return the initial maximum frame size in bytes. Default is 16384 bytes.
     */
    public int maxFrameSize() {
        return maxFrameSize;
    }

    /**
     * Sets the maximum allowed frame size.
     *
     * <p>The default is 16384 bytes.</p>
     *
     * <p>The maximum frame size determines the largest size of frames
     * that can be sent on the connection. Smaller sizes can lead to
     * lower latency, while larger sizes may improve throughput but require
     * more buffering.</p>
     *
     * <p>Limits:</p>
     * <ul>
     * <li>Minimum: 16384</li>
     * <li>Maximum: 2^24 - 1 (16777215)</li>
     * </ul>
     *
     * @param maxFrameSize the initial maximum frame size in bytes.
     * @return this builder
     * @throws IllegalArgumentException if the size is less than 16384 or greater than 16777215.
     */
    public Http2ConfigBuilder withMaxFrameSize(int maxFrameSize) {
        if (maxFrameSize < 16384 || maxFrameSize > 16777215) {
            throw new IllegalArgumentException("Maximum frame size must be between 16384 and 16777215 bytes.");
        }
        this.maxFrameSize = maxFrameSize;
        return this;
    }

    /**
     * Gets the maximum allowed size of request headers, or -1 to use the server default.
     *
     * @return the initial maximum header list size in bytes
     */
    public int maxHeaderListSize() {
        return maxHeaderListSize;
    }

    /**
     * <p>Specifies the maximum size in bytes of the HTTP request headers.</p>
     *
     * <p>This defaults to the value specified server-wide with {@link MuServerBuilder#withMaxHeadersSize(int)}.
     * Setting this value in the HTTP2 config therefore allows a different value for HTTP 1 vs HTTP 2 requests.</p>
     *
     * <p>Limits:</p>
     * <ul>
     * <li>Minimum: 3</li>
     * <li>No specified maximum</li>
     * </ul>
     *
     * @param maxHeaderListSize the initial maximum header list size in bytes, or <code>-1</code>
     *                                 to use the server wide setting.
     * @return this builder
     * @throws IllegalArgumentException if the size is less than 3 (and not -1).
     */
    public Http2ConfigBuilder withMaxHeaderListSize(int maxHeaderListSize) {
        if (maxHeaderListSize != -1 && maxHeaderListSize < 3) {
            throw new IllegalArgumentException("Maximum header list size must be non-negative.");
        }
        this.maxHeaderListSize = maxHeaderListSize;
        return this;
    }

    /**
     * Creates the HTTP2 settings object
     * @return A new Http2Config object
     */
    public Http2Config build() {

        Http2Settings initialSettings = new Http2Settings(
            false, maxHeaderTableSize, maxConcurrentStreams, initialWindowSize, maxFrameSize, maxHeaderListSize
        );
        return new Http2Config(enabled, initialSettings);
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
     * Creates a new config where HTTP2 is disabled
     * @return A new builder
     */
    public static Http2ConfigBuilder http2Disabled() {
        return new Http2ConfigBuilder().enabled(false);
    }


    /**
     * <p>Creates a new config where HTTP2 is enabled if supported by the Java version.</p>
     * <p>The current logic may not always return the correct results. It does not actually test for availability
     * and instead tries to detect the Java version, and enables HTTP2 for Java 9 or later.</p>
     * @return A new builder
     * @deprecated It is always supported and now turned on by default
     */
    @Deprecated
    public static Http2ConfigBuilder http2EnabledIfAvailable() {
        return new Http2ConfigBuilder().enabled(true);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Http2ConfigBuilder that = (Http2ConfigBuilder) o;
        return enabled == that.enabled && maxHeaderTableSize == that.maxHeaderTableSize && maxConcurrentStreams == that.maxConcurrentStreams && initialWindowSize == that.initialWindowSize && maxFrameSize == that.maxFrameSize && maxHeaderListSize == that.maxHeaderListSize;
    }

    @Override
    public int hashCode() {
        return Objects.hash(enabled, maxHeaderTableSize, maxConcurrentStreams, initialWindowSize, maxFrameSize, maxHeaderListSize);
    }

    @Override
    public String toString() {
        return "Http2ConfigBuilder{" +
            "enabled=" + enabled +
            ", maxHeaderTableSize=" + maxHeaderTableSize +
            ", maxConcurrentStreams=" + maxConcurrentStreams +
            ", initialWindowSize=" + initialWindowSize +
            ", maxFrameSize=" + maxFrameSize +
            ", maxHeaderListSize=" + maxHeaderListSize +
            '}';
    }
}
