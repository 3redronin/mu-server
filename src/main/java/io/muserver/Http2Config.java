package io.muserver;

/**
 * Configuration settings for HTTP2
 * @see Http2ConfigBuilder
 */
public class Http2Config {
    static final long DEFAULT_MAX_CONCURRENT_STREAMS = 200;

    final boolean enabled;
    final long maxConcurrentStreams;

    Http2Config(boolean enabled, long maxConcurrentStreams) {
        this.enabled = enabled;
        this.maxConcurrentStreams = maxConcurrentStreams;
    }

    @Override
    public String toString() {
        return "Http2Config{" +
            "enabled=" + enabled +
            ", maxConcurrentStreams=" + maxConcurrentStreams +
            '}';
    }

    /**
     * @return A new HTTP2 config builder based on the current settings
     */
    public Http2ConfigBuilder toBuilder() {
        return new Http2ConfigBuilder()
            .enabled(enabled)
            .maxConcurrentStreams(maxConcurrentStreams);
    }
}
