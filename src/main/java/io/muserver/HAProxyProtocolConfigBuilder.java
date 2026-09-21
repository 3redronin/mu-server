package io.muserver;

import java.math.BigInteger;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Builds immutable PROXY protocol settings. Defaults: enabled, V1 and V2 supported,
 * a ten-second timeout from socket acceptance and a 65,535-byte v2 payload limit.
 * Only configure PROXY protocol on listeners restricted to trusted proxies.
 */
public final class HAProxyProtocolConfigBuilder {
    private boolean enabled = true;
    private Set<HAProxyProtocolVersion> supportedVersions = EnumSet.allOf(HAProxyProtocolVersion.class);
    private long timeoutMillis = 10_000;
    private int maxV2PayloadSize = 65_535;

    /** Creates a builder with PROXY enabled, both versions, a ten-second timeout and the full v2 payload limit. */
    public HAProxyProtocolConfigBuilder() { }

    /**
     * Creates a builder with PROXY enabled, both versions, a ten-second timeout and the full v2 payload limit.
     * @return a new config builder
     */
    public static HAProxyProtocolConfigBuilder config() { return new HAProxyProtocolConfigBuilder(); }

    /**
     * Enables or disables PROXY processing; default true.
     * @param enabled whether to require a preamble on every connection
     * @return this builder
     */
    public HAProxyProtocolConfigBuilder withEnabled(boolean enabled) { this.enabled = enabled; return this; }

    /**
     * Gets whether this builder enables PROXY processing.
     * @return whether PROXY is enabled; default true
     */
    public boolean enabled() { return enabled; }

    /**
     * Selects supported versions; defaults to V1 and V2. Copies the collection and removes duplicates.
     * Use {@link #withEnabled(boolean)} to disable support rather than an empty collection.
     * @param versions a nonempty collection without null elements
     * @return this builder
     * @throws NullPointerException if the collection or a member is null
     * @throws IllegalArgumentException if the collection is empty
     */
    public HAProxyProtocolConfigBuilder withSupportedVersions(Collection<HAProxyProtocolVersion> versions) {
        Objects.requireNonNull(versions, "versions");
        if (versions.isEmpty()) throw new IllegalArgumentException("At least one PROXY version is required");
        EnumSet<HAProxyProtocolVersion> copy = EnumSet.noneOf(HAProxyProtocolVersion.class);
        for (HAProxyProtocolVersion version : versions) copy.add(Objects.requireNonNull(version, "version"));
        supportedVersions = copy;
        return this;
    }

    /**
     * Gets the currently selected protocol versions.
     * @return an immutable snapshot in enum order; defaults to V1 and V2
     */
    public Collection<HAProxyProtocolVersion> supportedVersions() {
        return Collections.unmodifiableSet(EnumSet.copyOf(supportedVersions));
    }

    /**
     * Sets the overall preamble timeout from socket acceptance, including executor queue time.
     * Default is ten seconds, independently of HTTP timeouts. Fractional milliseconds are truncated.
     * @param duration a positive duration of at least one millisecond, representable in signed nanoseconds
     * @param unit the duration unit
     * @return this builder
     * @throws NullPointerException if unit is null
     * @throws IllegalArgumentException if below one millisecond or overflowing signed nanoseconds
     */
    public HAProxyProtocolConfigBuilder withTimeout(long duration, TimeUnit unit) {
        Objects.requireNonNull(unit, "unit");
        long millis = unit.toMillis(duration);
        BigInteger nanos = BigInteger.valueOf(duration).multiply(BigInteger.valueOf(unit.toNanos(1)));
        if (duration <= 0 || millis < 1 || nanos.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
            throw new IllegalArgumentException("PROXY timeout must be at least one millisecond and fit in nanoseconds");
        }
        timeoutMillis = millis;
        return this;
    }

    /**
     * Gets the overall preamble timeout.
     * @return the overall preamble timeout in milliseconds; default 10,000
     */
    public long timeoutMillis() { return timeoutMillis; }

    /**
     * Limits v2 payload bytes (addresses and TLVs), excluding the fixed 16-byte header.
     * Default is 65,535. Zero permits only otherwise-valid zero-payload headers.
     * Applies to all commands/families, including LOCAL and UNSPEC; does not affect v1's fixed 107-byte limit.
     * @param size the payload limit, from 0 through 65,535 inclusive
     * @return this builder
     * @throws IllegalArgumentException if outside the permitted range
     */
    public HAProxyProtocolConfigBuilder withMaxV2PayloadSize(int size) {
        if (size < 0 || size > 65_535) throw new IllegalArgumentException("V2 payload limit must be between 0 and 65535 bytes");
        maxV2PayloadSize = size;
        return this;
    }

    /**
     * Gets the v2 payload size limit.
     * @return the maximum v2 payload bytes, excluding the fixed header; default 65,535
     */
    public int maxV2PayloadSize() { return maxV2PayloadSize; }

    /**
     * Builds an immutable snapshot of the settings.
     * @return a new immutable config containing these settings
     */
    public HAProxyProtocolConfig build() {
        return new HAProxyProtocolConfig(enabled, supportedVersions, timeoutMillis, maxV2PayloadSize);
    }
}
