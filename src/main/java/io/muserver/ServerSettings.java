package io.muserver;

import java.util.List;

class ServerSettings {
    final int maxHeadersSize;
    final long requestReadTimeoutMillis;
    final long maxRequestSize;
    final int maxUrlSize;
    final List<RateLimiterImpl> rateLimiters;

    ServerSettings(int maxHeadersSize, long requestReadTimeoutMillis, long maxRequestSize, int maxUrlSize, List<RateLimiterImpl> rateLimiters) {
        this.maxHeadersSize = maxHeadersSize;
        this.requestReadTimeoutMillis = requestReadTimeoutMillis;
        this.maxRequestSize = maxRequestSize;
        this.maxUrlSize = maxUrlSize;
        this.rateLimiters = rateLimiters;
    }

    public boolean block(MuRequest request) {
        boolean allowed = true;
        if (rateLimiters != null) {
            for (RateLimiterImpl limiter : rateLimiters) {
            }
        }
        return !allowed;
    }

    @Override
    public String toString() {
        return "ServerSettings{" +
            ", maxHeadersSize=" + maxHeadersSize +
            ", requestReadTimeoutMillis=" + requestReadTimeoutMillis +
            ", maxRequestSize=" + maxRequestSize +
            ", maxUrlSize=" + maxUrlSize +
            ", rateLimiters=" + rateLimiters +
            '}';
    }
}
