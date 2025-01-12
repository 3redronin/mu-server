package io.muserver;

import org.jspecify.annotations.Nullable;

/**
 * A function that controls how rate limits are applied. See {@link MuServerBuilder#withRateLimiter(RateLimitSelector)}
 * for usage details.
 */
public interface RateLimitSelector {

    /**
     * Selects a rate limit bucket based on the current request.
     * @param request An incoming request
     * @return A rate limit object, or <code>null</code> to not apply rate limiting to this request.
     */
    @Nullable RateLimit select(MuRequest request);
}
