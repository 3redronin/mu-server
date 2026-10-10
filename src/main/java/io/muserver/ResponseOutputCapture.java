package io.muserver;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/** Separates a synchronous framing/encoding turn from its resumable transport writes. */
interface ResponseOutputCapture {
    Capture begin() throws IOException;

    interface Capture extends AutoCloseable {
        /** Stop intercepting output, even if the encoder failed, and make its produced prefix readable. */
        void finishRendering() throws IOException;
        /** One bounded write; null means that all captured output has been acknowledged. */
        @Nullable CompletableFuture<@Nullable Void> writeNext() throws IOException;
        @Override void close() throws IOException;
    }
}
