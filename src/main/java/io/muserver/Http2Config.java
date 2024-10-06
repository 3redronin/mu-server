package io.muserver;

import java.util.Objects;

/**
 * Configuration settings for HTTP2
 * @see Http2ConfigBuilder
 */
public class Http2Config {
    private final boolean enabled;
    private final Http2Settings initialSettings;
    Http2Config(boolean enabled, Http2Settings initialSettings) {
        this.enabled = enabled;
        this.initialSettings = initialSettings;
    }

    /**
     * @return A new HTTP2 config builder based on the current settings
     */
    public Http2ConfigBuilder toBuilder() {
        return new Http2ConfigBuilder()
            .withMaxHeaderTableSize(initialSettings.headerTableSize)
            .withMaxConcurrentStreams(initialSettings.maxConcurrentStreams)
            .withMaxFrameSize(initialSettings.maxFrameSize)
            .withMaxHeaderListSize(initialSettings.maxHeaderListSize)
            .withInitialWindowSize(initialSettings.initialWindowSize)
            .enabled(enabled);
    }

    /**
     * @return <code>true</code> if HTTP2 is enabled for this server; otherwise <code>false</code>.
     */
    public boolean enabled() {
        return enabled;
    }

    /**
     * Gets the maximum allowed header table size.
     *
     * @return the maximum allowed header table size in bytes
     */
    public int maxHeaderTableSize() {
        return initialSettings.headerTableSize;
    }

    /**
     * Gets the maximum allowed concurrent streams.
     *
     * @return the maximum allowed number of concurrent streams
     */
    public int maxConcurrentStreams() {
        return initialSettings.maxConcurrentStreams;
    }

    /**
     * Gets the initial window size.
     *
     * @return the initial window size in bytes
     */
    public int initialWindowSize() {
        return initialSettings.initialWindowSize;
    }

    /**
     * Gets the maximum allowed frame size.
     *
     * @return the maximum frame size in bytes.
     */
    public int maxFrameSize() {
        return initialSettings.maxFrameSize;
    }

    /**
     * Gets the maximum size of request headers.
     *
     * @return the maximum header list size in bytes
     */
    public int maxHeaderListSize() {
        return initialSettings.maxHeaderListSize;
    }

    Http2Settings initialSettings() {
        return initialSettings;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Http2Config that = (Http2Config) o;
        return enabled == that.enabled && Objects.equals(initialSettings, that.initialSettings);
    }

    @Override
    public int hashCode() {
        return Objects.hash(enabled, initialSettings);
    }

    @Override
    public String toString() {
        return "Http2Config{" +
            "enabled=" + enabled +
            ", initialSettings=" + initialSettings +
            '}';
    }
}
