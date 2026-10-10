package io.muserver;

import org.jspecify.annotations.Nullable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;

/**
 * One transport write at a time. The source is borrowed until completion, which includes transport
 * drain (and TLS ciphertext drain). Internal completion callbacks must only schedule protocol work.
 * Cancellation aborts/fails the transport; callers must not cancel the internal completion future.
 */
@FunctionalInterface
interface AsyncTransportOutput {
    CompletableFuture<@Nullable Void> write(ByteBuffer source) throws IOException;
}
