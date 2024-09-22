package io.muserver;

import java.util.concurrent.TimeUnit;

/**
 * Specifies the limits allowed for a single value, such as an IP address.
 */
public class RateLimit {
    final String bucket;
    final long allowed;
    final RateLimitRejectionAction action;
    final long per;
    final TimeUnit perUnit;

    RateLimit(String bucket, long allowed, RateLimitRejectionAction action, long per, TimeUnit perUnit) {
        this.bucket = bucket;
        this.allowed = allowed;
        this.action = action;
        this.per = per;
        this.perUnit = perUnit;
    }

    /**
     * Creates a new rate limit builder
     * @return a new builder
     */
    public static RateLimitBuilder builder() {
        return new RateLimitBuilder();
    }

    long expiryMillis() {
        return this.perUnit.toMillis(this.per);
    }
}
