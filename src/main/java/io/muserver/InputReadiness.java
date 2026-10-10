package io.muserver;

import org.jspecify.annotations.Nullable;
import java.util.concurrent.CompletableFuture;

/** One pending input notification, guarded by the source's storage lock. */
final class InputReadiness {
    private @Nullable CompletableFuture<Void> waiting;

    CompletableFuture<Void> whenReadable(boolean readable) {
        if (readable) return CompletableFuture.completedFuture(null);
        if (waiting == null || waiting.isDone()) waiting = new CompletableFuture<>();
        return waiting;
    }

    @Nullable CompletableFuture<Void> take() {
        CompletableFuture<Void> result = waiting;
        waiting = null;
        return result;
    }

    /** Call after releasing the storage lock; listeners may only schedule internal work. */
    static void signal(@Nullable CompletableFuture<Void> ready) {
        if (ready != null) ready.complete(null);
    }
}
