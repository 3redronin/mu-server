package io.muserver;

/**
 * Configuration settings for HTTP2
 * @see Http2ConfigBuilder
 */
public class Http2Config {
    final boolean enabled;

    Http2Config(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public String toString() {
        return "Http2Config{" +
            "enabled=" + enabled +
            '}';
    }
}
