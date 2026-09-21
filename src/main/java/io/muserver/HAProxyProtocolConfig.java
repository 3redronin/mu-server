package io.muserver;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Immutable PROXY protocol listener settings. New builders enable both versions,
 * allow 65,535 v2 payload bytes and use a ten-second overall preamble timeout.
 * A server with no configuration leaves PROXY protocol disabled.
 * @see HAProxyProtocolConfigBuilder
 */
public final class HAProxyProtocolConfig {
    private final boolean enabled;
    private final Set<HAProxyProtocolVersion> supportedVersions;
    private final long timeoutMillis;
    private final int maxV2PayloadSize;

    HAProxyProtocolConfig(boolean enabled, Collection<HAProxyProtocolVersion> supportedVersions,
                         long timeoutMillis, int maxV2PayloadSize) {
        this.enabled = enabled;
        this.supportedVersions = Collections.unmodifiableSet(EnumSet.copyOf(supportedVersions));
        this.timeoutMillis = timeoutMillis;
        this.maxV2PayloadSize = maxV2PayloadSize;
    }

    /**
     * Indicates whether every connection requires a PROXY preamble before TLS or HTTP.
     * @return whether enabled; default true on a new config builder
     */
    public boolean enabled() { return enabled; }

    /**
     * Gets the permitted wire formats.
     * @return an immutable collection in enum order; defaults to V1 and V2
     */
    public Collection<HAProxyProtocolVersion> supportedVersions() { return supportedVersions; }

    /**
     * Gets the overall preamble timeout, measured from socket acceptance including queued work.
     * HTTP timeouts do not affect this deadline.
     * @return the timeout in milliseconds; default 10,000
     */
    public long timeoutMillis() { return timeoutMillis; }

    /**
     * Gets the maximum v2 payload length, including addresses and TLVs but excluding the fixed 16-byte header.
     * The limit applies to LOCAL and UNSPEC too; oversized payloads are rejected before being read.
     * @return the payload limit in bytes, from 0 through 65,535; default 65,535
     */
    public int maxV2PayloadSize() { return maxV2PayloadSize; }

    /**
     * Creates an independently mutable builder with these settings.
     * @return a builder initialized from this config
     */
    public HAProxyProtocolConfigBuilder toBuilder() {
        return HAProxyProtocolConfigBuilder.config().withEnabled(enabled)
            .withSupportedVersions(supportedVersions).withTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .withMaxV2PayloadSize(maxV2PayloadSize);
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof HAProxyProtocolConfig)) return false;
        HAProxyProtocolConfig that = (HAProxyProtocolConfig) other;
        return enabled == that.enabled && timeoutMillis == that.timeoutMillis
            && maxV2PayloadSize == that.maxV2PayloadSize && supportedVersions.equals(that.supportedVersions);
    }

    @Override public int hashCode() { return Objects.hash(enabled, supportedVersions, timeoutMillis, maxV2PayloadSize); }

    @Override public String toString() {
        return "HAProxyProtocolConfig{enabled=" + enabled + ", supportedVersions=" + supportedVersions
            + ", timeoutMillis=" + timeoutMillis + ", maxV2PayloadSize=" + maxV2PayloadSize + '}';
    }
}
