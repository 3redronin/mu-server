package io.muserver;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.jspecify.annotations.Nullable;

/** Lets the connection mailbox suspend legacy callback events without retaining a worker. */
final class WebSocketCompatibility {
    private static final ThreadLocal<Context> current = new ThreadLocal<>();

    @FunctionalInterface
    interface Event { void run() throws Exception; }

    static @Nullable CompletableFuture<@Nullable Void> invoke(Event event) throws Exception {
        Context previous = current.get();
        Context context = new Context();
        current.set(context);
        try {
            event.run();
            return context.completion;
        } finally {
            if (previous == null) current.remove();
            else current.set(previous);
        }
    }

    static void awaitOrDefer(CompletableFuture<@Nullable Void> completion) throws Exception {
        Context context = current.get();
        if (context != null) {
            context.completion = completion;
            return;
        }
        try { completion.get(); }
        catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw e;
        }
    }

    private static final class Context {
        @Nullable CompletableFuture<@Nullable Void> completion;
    }
}
