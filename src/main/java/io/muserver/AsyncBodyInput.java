package io.muserver;

import org.jspecify.annotations.Nullable;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/** Exclusive body-reader view; zero means input is temporarily unavailable, minus one is EOF. */
interface AsyncBodyInput {
    interface Provider {
        @Nullable AsyncBodyInput asynchronousInput();
    }

    int readAvailable(byte[] target) throws IOException;

    /** The notification has no borrowed storage and may be cancelled when the reader retires. */
    CompletableFuture<Void> whenReadable();

    long readTimeoutMillis();

    RuntimeException timeoutFailure();
}
