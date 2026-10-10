package io.muserver;

import java.util.Map;

/**
 * A rate limiter. A limiter is created when {@link MuServerBuilder#withRateLimiter(RateLimitSelector)} is used.
 * <p>Mu periodically removes expired request history and empty buckets in the background.</p>
 */
public interface RateLimiter {

    /**
     * Gets a snapshot of the current request counts for each non-empty rate-limit bucket.
     *
     * @return An unmodifiable map of bucket names to request counts
     */
    Map<String, Long> currentBuckets();

    /**
     * Gets the selector used to create this rate limiter.
     *
     * @return The selector that was passed to {@link MuServerBuilder#withRateLimiter(RateLimitSelector)}
     */
    RateLimitSelector selector();
}
