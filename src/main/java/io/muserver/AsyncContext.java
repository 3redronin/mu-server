package io.muserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @deprecated This interface is no longer used. Instead call {@link MuRequest#handleAsync()} from a standard Mu Handler.
 */
@Deprecated
public class AsyncContext implements ResponseInfo {
    private static final Logger log = LoggerFactory.getLogger(AsyncContext.class);
    public final MuRequest request;
    public final MuResponse response;
    private final ResponseCompleteListener completedCallback;

    /**
     * @deprecated Use {@link MuRequest#attribute(String)} instead.
     */
    @Deprecated
    public Object state;

    GrowableByteBufferInputStream requestBody;
    private AtomicBoolean completed = new AtomicBoolean(false);

    AsyncContext(MuRequest request, MuResponse response, ResponseCompleteListener completedCallback) {
        this.request = request;
        this.response = response;
        this.completedCallback = completedCallback;
    }

    public Future<Void> complete(boolean forceDisconnect) {
        boolean wasCompleted = this.completed.getAndSet(true);
        if (wasCompleted) {
            log.debug("AsyncContext.complete called twice for " + request);
            return null;
        } else {
            Future<Void> complete = ((NettyResponseAdaptor) response)
                .complete(forceDisconnect);
            completedCallback.onComplete(this);
            return complete;
        }
    }

    boolean isComplete() {
        return completed.get();
    }

    void onCancelled(boolean forceDisconnect) {
        boolean wasCompleted = isComplete();
        ((NettyResponseAdaptor) response).onCancelled();
        ((NettyRequestAdapter) request).onCancelled();
        if (!wasCompleted) {
            complete(forceDisconnect);
        }
    }

    @Override
    public long duration() {
        return System.currentTimeMillis() - request.startTime();
    }

    @Override
    public boolean completedSuccessfully() {
        return !((NettyResponseAdaptor) response).clientDisconnected();
    }

    @Override
    public MuRequest request() {
        return request;
    }

    @Override
    public MuResponse response() {
        return response;
    }
}
