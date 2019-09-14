package io.muserver;

import java.util.concurrent.TimeUnit;

/**
 * A builder to create {@link RateLimit} objects. The built limit is returned by a {@link RateLimitSelector}.
 * See {@link MuServerBuilder#withRateLimiter(RateLimitSelector)} for more details on how to use this.
 */
public class RateLimitBuilder {

    private long rate = 100;
    private long per = 1;
    private TimeUnit perUnit = TimeUnit.SECONDS;
    private String bucket;
    private RateLimitRejectionAction action = RateLimitRejectionAction.SEND_429;

    /**
     * Sets the allowed rate. For example, this would be 10 if you allowed 10 per second.
     * @param rate The rate to use for this bucket
     * @return This builder
     * @throws IllegalArgumentException if the parameter is 0 or negative.
     */
    public RateLimitBuilder withRate(long rate) {
        if (rate < 1) {
            throw new IllegalArgumentException("Invalid rate (" + rate + ") for the rate limit");
        }
        this.rate = rate;
        return this;
    }

    /**
     * The period that the limit applies to. For example, this would be 1 second if you allowed 10 per 1 second.
     * The default is 1 second.
     * @param period The time period of the limit.
     * @param unit The unit of the period
     * @return This builder.
     * @throws IllegalArgumentException if period is less than 1, or unit is null.
     */
    public RateLimitBuilder withWindow(long period, TimeUnit unit) {
        if (period < 1) {
            throw new IllegalArgumentException("Invalid period (" + period + ") for the rate limit");
        }
        Mutils.notNull("unit", unit);
        this.per = period;
        this.perUnit = unit;
        return this;
    }

    /**
     * Sets the bucket that the limit applies to.
     * @param name The bucket, for example when limiting by IP address this would be an IP address.
     * @return This builder.
     */
    public RateLimitBuilder withBucket(String name) {
        this.bucket = name;
        return this;
    }

    /**
     * Specifies what to do when this limit is breached.
     * @param action The action to take
     * @return This builder
     * @throws IllegalArgumentException If the action is null
     */
    public RateLimitBuilder withRejectionAction(RateLimitRejectionAction action) {
        Mutils.notNull("action", action);
        this.action = action;
        return this;
    }

    /**
     * Creates a new builder
     * @return A new rate limit builder
     */
    public static RateLimitBuilder rateLimit() {
        return new RateLimitBuilder();
    }

    /**
     * Creates the rate limit from the builder
     * @return a new Rate Limit
     */
    public RateLimit build() {
        return new RateLimit(bucket, rate, action, per, perUnit);
    }

}
