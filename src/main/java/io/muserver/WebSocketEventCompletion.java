package io.muserver;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.jspecify.annotations.Nullable;

/**
 * Lets an asynchronous receive callback return its application worker while the connection
 * waits for its completion callback before delivering another event or reusing the input buffer.
 * The internal connection reader still waits for completion.
 */
final class WebSocketEventCompletion {
    // Passes the completion future through the unchanged void receive methods. This context
    // exists only during invocation; asynchronous completion uses the captured future directly.
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
        // Direct calls outside connection dispatch retain their blocking behaviour.
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
