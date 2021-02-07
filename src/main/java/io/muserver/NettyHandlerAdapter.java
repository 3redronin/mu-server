package io.muserver;

import io.netty.buffer.ByteBuf;
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

    static void passDataToHandler(ByteBuf data, HttpExchange httpExchange, DoneCallback dataQueuedCallback) {
//        if (data.readableBytes() > 0) {
//            data.retain();
//            try {
//                httpExchange.requestBody.handOff(data, error -> {
//                    data.release();
//                    dataQueuedCallback.onComplete(error);
//                });
//            } catch (Exception e) {
//                data.release();
//                if (e instanceof MuException) {
//                    MuResponse resp = httpExchange.response;
//                    if (!resp.hasStartedSendingData()) {
//                        resp.status(413);
//                        resp.contentType(ContentTypes.TEXT_PLAIN_UTF8);
//                        resp.headers().set(HeaderNames.CONNECTION, HeaderValues.CLOSE);
//                        resp.write("413 Payload Too Large");
//                    } else {
//                        httpExchange.onCancelled(ResponseState.ERRORED);
//                    }
//                }
//            }
//        } else {
//            try {
//                dataQueuedCallback.onComplete(null);
//            } catch (Exception ignored) {
//            }
//        }
    }

    void onHeaders(HttpExchange muCtx, MuStatsImpl serverStats, MuStatsImpl connectionStats) {

        executor.execute(() -> {
            if (muCtx.state().endState()) {
                return;
            }
            NettyRequestAdapter request = muCtx.request;
            NettyResponseAdaptor response = muCtx.response;
            boolean error = false;
            try {
                serverStats.onRequestStarted(request);
                connectionStats.onRequestStarted(request);

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
