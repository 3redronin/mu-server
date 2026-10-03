package io.muserver;

import java.util.Map;

/**
 * A rate limiter. A limiter is created when {@link MuServerBuilder#withRateLimiter(RateLimitSelector)} is used.
 * <p>Mu periodically reclaims expired bucket history in the background, including when no further
 * requests arrive. Calling {@link #currentBuckets()} is not required for this cleanup.</p>
 */
public interface RateLimiter {

    /**
     * Gets the current request counts for each rate-limit bucket.
     *
     * @return A map of the current bucket names to the number of requests in each bucket
     */
    Map<String, Long> currentBuckets();

    /**
     * Gets the selector used to create this rate limiter.
     *
     * @return The selector that was passed to {@link MuServerBuilder#withRateLimiter(RateLimitSelector)}
     */
    RateLimitSelector selector();
}
