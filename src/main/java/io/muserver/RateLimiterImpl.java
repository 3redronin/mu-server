package io.muserver;

import io.netty.util.HashedWheelTimer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

class RateLimiterImpl implements RateLimiter {
    private final Logger log = LoggerFactory.getLogger(RateLimiterImpl.class);

    private final RateLimitSelector selector;
    private final ConcurrentHashMap<String, AtomicLong> map = new ConcurrentHashMap<>();
    private final HashedWheelTimer timer;

    RateLimiterImpl(RateLimitSelector selector, HashedWheelTimer timer) {
        this.selector = selector;
        this.timer = timer;
    }

    boolean record(MuRequest request) {
        RateLimit rateLimit = selector.select(request);
        if (rateLimit == null || rateLimit.bucket == null) {
            return true;
        }
        String name = rateLimit.bucket;
        AtomicLong counter = map.computeIfAbsent(name, s -> new AtomicLong(0));
        long curVal = counter.get();

        if (curVal >= rateLimit.allowed) {
            log.info("Rate limit for " + name + " exceeded. Action: " + rateLimit.action);
            if (rateLimit.action == RateLimitRejectionAction.SEND_429) {
                return false;
            }
        } else {
            counter.incrementAndGet();
            timer.newTimeout(timeout -> {
                long newVal = counter.decrementAndGet();
                if (newVal <= 0) {
                    map.remove(name);
                }
            }, rateLimit.per, rateLimit.perUnit);
        }
        return true;
    }

    @Override
    public Map<String, Long> currentBuckets() {
        HashMap<String,Long> copy = new HashMap<>();
        for (Map.Entry<String, AtomicLong> entry : map.entrySet()) {
            copy.put(entry.getKey(), entry.getValue().longValue());
        }
        return Collections.unmodifiableMap(copy);
    }

    @Override
    public RateLimitSelector selector() {
        return selector;
    }

    @Override
    public String toString() {
        return "RateLimiterImpl{" +
            "buckets=" + map +
            '}';
    }
}
