package io.muserver;

import java.util.Map;

/**
 * A rate limiter. A limiter is created when {@link MuServerBuilder#withRateLimiter(RateLimitSelector)} is used.
 */
public interface RateLimiter {

    /**
     * @return A map of the current bucket names to the number of requests in each bucket
     */
    Map<String, Long> currentBuckets();

    /**
     * @return The selector that was passed to {@link MuServerBuilder#withRateLimiter(RateLimitSelector)}
     */
    RateLimitSelector selector();
}
