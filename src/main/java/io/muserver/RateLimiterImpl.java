package io.muserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

class RateLimiterImpl implements RateLimiter {
    private final Logger log = LoggerFactory.getLogger(RateLimiterImpl.class);

    private final Lock lock = new ReentrantLock();
    private final RateLimitSelector selector;
    private final Map<String, Queue<Instant>> map = new HashMap<>();

    RateLimiterImpl(RateLimitSelector selector) {
        this.selector = selector;
    }

    private void removeExpired(String bucket, Queue<Instant> queue) {
        if (queue == null) {
            return;
        }
        var cutoff = Instant.now();
        var head = queue.peek();
        while (head != null) {
            if (head.isBefore(cutoff)) {
                queue.poll();
                if (queue.isEmpty()) {
                    map.remove(bucket);
                    head = null;
                } else {
                    head = queue.peek();
                }
            } else {
                break;
            }
        }
    }

    RateLimitRejectionAction record(MuRequest request) {
        RateLimit rateLimit = selector.select(request);
        if (rateLimit == null || rateLimit.bucket == null) {
            return null;
        }
        String name = rateLimit.bucket;
        RateLimitRejectionAction action = null;
        Instant nextExpiry = null;
        lock.lock();
        try {
            removeExpired(name, map.get(name));
            var queue = map.computeIfAbsent(name, s -> new LinkedList<>());
            long curVal = queue.size();
            if (curVal >= rateLimit.allowed) {
                action = rateLimit.action;
                if (action == RateLimitRejectionAction.SEND_429) {
                    nextExpiry = queue.peek();
                }
            } else {
                queue.add(Instant.now().plusMillis(rateLimit.expiryMillis()));
            }
        } finally {
            lock.unlock();
        }
        if (action != null) {
            log.info("Rate limit for {} exceeded. Action: {}", rateLimit.bucket, rateLimit.action);
            if (action == RateLimitRejectionAction.SEND_429 && request != null && nextExpiry != null) {
                long secondsToNext = (nextExpiry.toEpochMilli() - System.currentTimeMillis()) / 1000L;
                long fuzz = (long) (Math.random() * 20.0);
                request.headers().set(HeaderNames.RETRY_AFTER, secondsToNext + fuzz);
            }
        }
        return action;
    }

    @Override
    public Map<String, Long> currentBuckets() {
        HashMap<String, Long> copy = new HashMap<>();
        lock.lock();
        try {
            for (Map.Entry<String, Queue<Instant>> entry : map.entrySet()) {
                String bucket = entry.getKey();
                Queue<Instant> expiries = entry.getValue();
                removeExpired(bucket, expiries);
                copy.put(bucket, (long) expiries.size());
            }
        } finally {
            lock.unlock();
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
