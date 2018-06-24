package io.muserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @deprecated This interface is no longer used. Instead call {@link MuRequest#handleAsync()} from a standard Mu Handler.
 */
@Deprecated
public class AsyncContext {
    private static final Logger log = LoggerFactory.getLogger(AsyncContext.class);
	public final MuRequest request;
	public final MuResponse response;
    private final MuStatsImpl stats;
    public Object state;
	GrowableByteBufferInputStream requestBody;
	private AtomicBoolean completed = new AtomicBoolean(false);

	public AsyncContext(MuRequest request, MuResponse response, MuStatsImpl stats) {
		this.request = request;
		this.response = response;
        this.stats = stats;
    }

	public Future<Void> complete() {
        boolean wasCompleted = this.completed.getAndSet(true);
        if (wasCompleted) {
	        log.info("AsyncContext.complete called twice for " + request);
	        return null;
        } else {
            stats.onRequestEnded(request);
            return ((NettyResponseAdaptor) response).complete();
        }
	}
}
