package io.muserver;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

class RateLimiterImpl implements RateLimiter {
    private final Logger log = LoggerFactory.getLogger(RateLimiterImpl.class);

    private final Lock lock = new ReentrantLock();
    private final RateLimitSelector selector;
    private final LongSupplier nanoTime;
    private final Map<String, Queue<Long>> map = new HashMap<>();

    RateLimiterImpl(RateLimitSelector selector) {
        this(selector, System::nanoTime);
    }

    RateLimiterImpl(RateLimitSelector selector, LongSupplier nanoTime) {
        this.selector = selector;
        this.nanoTime = nanoTime;
    }

    private static void removeExpired(
        @Nullable Queue<Long> queue,
        long nowNanos
    ) {
        if (queue == null) {
            return;
        }
        var head = queue.peek();
        while (head != null
            && MonotonicTime.nanosUntil(head, nowNanos) <= 0L) {
            queue.poll();
            head = queue.peek();
        }
    }

    @Nullable RateLimitRejectionAction record(MuRequest request) {
        RateLimit rateLimit = selector.select(request);
        if (rateLimit == null || rateLimit.bucket == null) {
            return null;
        }
        String name = rateLimit.bucket;
        RateLimitRejectionAction action = null;
        Long nextExpiryNanos = null;
        lock.lock();
        try {
            long nowNanos = nanoTime.getAsLong();
            Queue<Long> existing = map.get(name);
            removeExpired(existing, nowNanos);
            if (existing != null && existing.isEmpty()) {
                map.remove(name);
            }
            var queue = map.computeIfAbsent(name, s -> new ArrayDeque<>());
            long curVal = queue.size();
            if (curVal >= rateLimit.allowed) {
                action = rateLimit.action;
                if (action == RateLimitRejectionAction.SEND_429) {
                    nextExpiryNanos = queue.peek();
                }
            } else {
                queue.add(
                    MonotonicTime.deadlineAfter(
                        nowNanos,
                        rateLimit.expiryNanos()
                    )
                );
            }
        } finally {
            lock.unlock();
        }
        if (action != null) {
            log.info("Rate limit for {} exceeded. Action: {}", rateLimit.bucket, rateLimit.action);
            if (action == RateLimitRejectionAction.SEND_429
                && request != null
                && nextExpiryNanos != null) {
                long remainingNanos = Math.max(
                    0L,
                    MonotonicTime.nanosUntil(
                        nextExpiryNanos,
                        nanoTime.getAsLong()
                    )
                );
                long secondsToNext =
                    TimeUnit.NANOSECONDS.toSeconds(remainingNanos);
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
            long nowNanos = nanoTime.getAsLong();
            Iterator<Map.Entry<String, Queue<Long>>> iterator =
                map.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, Queue<Long>> entry = iterator.next();
                String bucket = entry.getKey();
                Queue<Long> expiries = entry.getValue();
                removeExpired(expiries, nowNanos);
                if (expiries.isEmpty()) {
                    iterator.remove();
                } else {
                    copy.put(bucket, (long) expiries.size());
                }
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
        lock.lock();
        try {
            return "RateLimiterImpl{" +
                "buckets=" + map +
                '}';
        } finally {
            lock.unlock();
        }
    }
}
