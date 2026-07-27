package io.muserver;

import org.jspecify.annotations.Nullable;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Creates authenticated automatic ping payloads and measures their echoed
 * pongs. RFC 6455 section 5.5.3 requires a pong response to copy the ping's
 * application data, so each payload can carry its own authenticated monotonic
 * send point without shared outstanding-ping state.
 */
final class WebsocketPingTracker {
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int SECRET_LENGTH = 32;
    private static final int TAG_LENGTH = 8;
    private static final int PAYLOAD_LENGTH = Long.BYTES + TAG_LENGTH;

    private final byte[] connectionSecret;

    WebsocketPingTracker() {
        this(randomSecret());
    }

    WebsocketPingTracker(byte[] connectionSecret) {
        if (connectionSecret.length == 0) {
            throw new IllegalArgumentException("The connection secret must not be empty");
        }
        this.connectionSecret = connectionSecret.clone();
    }

    ByteBuffer newPingPayload() {
        return newPingPayload(System.nanoTime());
    }

    ByteBuffer newPingPayload(long sentNanos) {
        return ByteBuffer.allocate(PAYLOAD_LENGTH)
            .putLong(sentNanos)
            .put(authenticationTag(sentNanos))
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
        long sentNanos = pongPayload.getLong(payloadOffset);
        byte[] suppliedTag = new byte[TAG_LENGTH];
        ByteBuffer copy = pongPayload.duplicate();
        copy.position(payloadOffset + Long.BYTES);
        copy.get(suppliedTag);
        if (!MessageDigest.isEqual(authenticationTag(sentNanos), suppliedTag)) {
            return null;
        }
        return MonotonicTime.elapsedMillis(sentNanos, receivedNanos);
    }

    private byte[] authenticationTag(long sentNanos) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(connectionSecret, HMAC_ALGORITHM));
            byte[] timestamp = ByteBuffer.allocate(Long.BYTES)
                .putLong(sentNanos)
                .array();
            return Arrays.copyOf(mac.doFinal(timestamp), TAG_LENGTH);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(
                HMAC_ALGORITHM + " is unavailable",
                e
            );
        }
    }

    private static byte[] randomSecret() {
        byte[] secret = new byte[SECRET_LENGTH];
        HttpsConfigBuilder.random.nextBytes(secret);
        return secret;
    }
}
