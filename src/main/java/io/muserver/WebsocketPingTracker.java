package io.muserver;

import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;

/**
 * Creates self-identifying automatic ping payloads and measures their echoed
 * pongs. RFC 6455 section 5.5.3 requires a pong response to copy the ping's
 * application data, so each payload can carry its own monotonic send point
 * without shared outstanding-ping state.
 */
final class WebsocketPingTracker {
    private static final int TOKEN_LENGTH = 8;
    private static final int PAYLOAD_LENGTH = TOKEN_LENGTH + Long.BYTES;

    private final byte[] connectionToken;

    WebsocketPingTracker() {
        this(randomToken());
    }

    WebsocketPingTracker(byte[] connectionToken) {
        if (connectionToken.length != TOKEN_LENGTH) {
            throw new IllegalArgumentException("The connection token must be 8 bytes");
        }
        this.connectionToken = connectionToken.clone();
    }

    ByteBuffer newPingPayload() {
        return newPingPayload(System.nanoTime());
    }

    ByteBuffer newPingPayload(long sentNanos) {
        return ByteBuffer.allocate(PAYLOAD_LENGTH)
            .put(connectionToken)
            .putLong(sentNanos)
            .flip();
    }

    @Nullable
    Long pongLatencyMillis(ByteBuffer pongPayload) {
        return pongLatencyMillis(pongPayload, System.nanoTime());
    }

    @Nullable
    Long pongLatencyMillis(ByteBuffer pongPayload, long receivedNanos) {
        if (pongPayload.remaining() != PAYLOAD_LENGTH) {
            return null;
        }
        int payloadOffset = pongPayload.position();
        for (int i = 0; i < TOKEN_LENGTH; i++) {
            if (connectionToken[i] != pongPayload.get(payloadOffset + i)) {
                return null;
            }
        }
        long sentNanos = pongPayload.getLong(payloadOffset + TOKEN_LENGTH);
        return MonotonicTime.elapsedMillis(sentNanos, receivedNanos);
    }

    private static byte[] randomToken() {
        byte[] token = new byte[TOKEN_LENGTH];
        HttpsConfigBuilder.random.nextBytes(token);
        return token;
    }
}
