package io.muserver;

/**
 * A function that controls how rate limits are applied. See {@link MuServerBuilder#withRateLimiter(RateLimitSelector)}
 * for usage details.
 */
public interface RateLimitSelector {

    /**
     * Selects a rate limit bucket based on the current request.
     * @param request An incoming request
     * @return A rate limit object, or null to not apply rate limiting to this request.
     */
    RateLimit select(MuRequest request);
}
