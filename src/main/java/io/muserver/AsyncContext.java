package io.muserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

class AsyncContext {
    private static final Logger log = LoggerFactory.getLogger(AsyncContext.class);
    public final NettyRequestAdapter request;
    public final MuResponse response;
    private final MuStatsImpl stats;
    private final Runnable onComplete;
    GrowableByteBufferInputStream requestBody;
    private AtomicBoolean completed = new AtomicBoolean(false);

    AsyncContext(NettyRequestAdapter request, MuResponse response, MuStatsImpl stats, Runnable onComplete) {
        this.request = request;
        this.response = response;
        this.stats = stats;
        this.onComplete = onComplete;
    }

    public Future<Void> complete(boolean forceDisconnect) {
        boolean wasCompleted = this.completed.getAndSet(true);
        if (wasCompleted) {
            log.info("AsyncContext.complete called twice for " + request);
            return null;
        } else {
            request.clean();
            Future<Void> complete = ((NettyResponseAdaptor) response)
                .complete(forceDisconnect);
            stats.onRequestEnded(request);
            onComplete.run();
            return complete;
        }
    }

    boolean isComplete() {
        return completed.get();
    }

    void onDisconnected() {
        boolean wasCompleted = isComplete();
        request.onClientDisconnected(wasCompleted);
        if (!wasCompleted) {
            complete(true);
        }
    }
}
