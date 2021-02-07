package io.muserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.NotFoundException;
import java.util.List;
import java.util.concurrent.ExecutorService;

import static io.muserver.HttpExchange.dealWithUnhandledException;

class NettyHandlerAdapter {


    private static final Logger log = LoggerFactory.getLogger(NettyHandlerAdapter.class);
    private final List<MuHandler> muHandlers;
    private final ExecutorService executor;
    private final List<ResponseCompleteListener> completeListeners;

    NettyHandlerAdapter(ExecutorService executor, List<MuHandler> muHandlers, List<ResponseCompleteListener> completeListeners) {
        this.executor = executor;
        this.muHandlers = muHandlers;
        this.completeListeners = completeListeners;
    }

    void onHeaders(HttpExchange muCtx) {

        executor.execute(() -> {
            if (muCtx.state().endState()) {
                return;
            }
            NettyRequestAdapter request = muCtx.request;
            NettyResponseAdaptor response = muCtx.response;
            boolean error = false;
            try {
                boolean handled = false;
                for (MuHandler muHandler : muHandlers) {
                    handled = muHandler.handle(request, response);
                    if (handled) {
                        break;
                    }
                    if (request.isAsync()) {
                        throw new IllegalStateException(muHandler.getClass() + " returned false however this is not allowed after starting to handle a request asynchronously.");
                    }
                }
                if (!handled) {
                    throw new NotFoundException();
                }

            } catch (Throwable ex) {
                error = dealWithUnhandledException(request, response, ex);
            } finally {
                if ((error || !request.isAsync()) && !response.outputState().endState()) {
                    try {
                        muCtx.complete(error);
                    } catch (Throwable e) {
                        log.warn("Error while completing request", e);
                    }
                }
            }
        });
    }

    void onResponseComplete(ResponseInfo info, MuStatsImpl serverStats, MuStatsImpl connectionStats) {
        connectionStats.onRequestEnded(info.request());
        serverStats.onRequestEnded(info.request());
        if (completeListeners != null) {
            for (ResponseCompleteListener listener : completeListeners) {
                try {
                    listener.onComplete(info);
                } catch (Exception e) {
                    log.error("Error from completion listener", e);
                }
            }
        }
    }
}
