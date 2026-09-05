package io.muserver;

import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Matches automatic pongs to outstanding pings. Each ping contributes at most
 * one latency sample. The bounded ledger tolerates overlapping and missing
 * pongs without retaining an unbounded history or accepting replayed samples.
 */
final class WebsocketPingTracker {
    static final int MAX_OUTSTANDING = 64;
    private final Map<UUID, Long> outstanding = new LinkedHashMap<>();

    ByteBuffer newPingPayload() {
        return newPingPayload(System.nanoTime());
    }

    synchronized ByteBuffer newPingPayload(long sentNanos) {
        UUID id = UUID.randomUUID();
        if (outstanding.size() == MAX_OUTSTANDING) {
            var oldest = outstanding.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
        outstanding.put(id, sentNanos);
        return ByteBuffer.allocate(16)
            .putLong(id.getMostSignificantBits())
            .putLong(id.getLeastSignificantBits())
            .flip();
    }

    @Nullable Long pongLatencyMillis(ByteBuffer payload) {
        return pongLatencyMillis(payload, System.nanoTime());
    }

    synchronized @Nullable Long pongLatencyMillis(ByteBuffer payload, long receivedNanos) {
        if (payload.remaining() != 16) {
            return null;
        }
        int offset = payload.position();
        Long sentNanos = outstanding.remove(new UUID(payload.getLong(offset), payload.getLong(offset + 8)));
        return sentNanos == null ? null : MonotonicTime.elapsedMillis(sentNanos, receivedNanos);
    }
}
