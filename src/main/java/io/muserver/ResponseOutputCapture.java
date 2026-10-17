package io.muserver;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/** Separates a synchronous framing/encoding turn from its resumable transport writes. */
interface ResponseOutputCapture {
    Capture begin() throws IOException;

    /** Input headroom predicts framed output size only when no content encoder is selected. */
    default boolean inputHeadroomUseful() { return false; }

    /** Only unencoded output with a bounded, fair fragment scheduler may retain a larger heap borrow. */
    default boolean multiFrameBorrowAllowed() { return false; }

    interface Capture extends AutoCloseable {
        /**
         * The renderer retains this range unchanged until this capture drains. An unencoded
         * response may borrow it if it is the capture's sole, unchanged array write.
         * Encoders and mixed output must retain the ordinary owned-copy behavior.
         */
        default void borrowSourceIfUnchanged(byte[] source, int offset, int length) throws IOException { }
        /** Stop intercepting output, even if the encoder failed, and make its produced prefix readable. */
        void finishRendering() throws IOException;
        /** One bounded write; null means that all captured output has been acknowledged. */
        @Nullable CompletableFuture<@Nullable Void> writeNext() throws IOException;
        @Override void close() throws IOException;
    }
}
