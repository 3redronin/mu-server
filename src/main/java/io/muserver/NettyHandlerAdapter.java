package io.muserver;

import io.muserver.rest.MuRuntimeDelegate;
import io.netty.buffer.ByteBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

class NettyHandlerAdapter {

    private static final Map<String, String> exceptionMessageMap = new HashMap<>();
    static {
        MuRuntimeDelegate.ensureSet();
        exceptionMessageMap.put(new NotFoundException().getMessage(), "This page is not available. Sorry about that.");
    }

    private static final Logger log = LoggerFactory.getLogger(NettyHandlerAdapter.class);
    private final List<MuHandler> muHandlers;
    private final ServerSettings settings;
    private final ExecutorService executor;

    NettyHandlerAdapter(ExecutorService executor, List<MuHandler> muHandlers, ServerSettings settings) {
        this.executor = executor;
        this.muHandlers = muHandlers;
        this.settings = settings;
    }

    static void passDataToHandler(ByteBuf data, AsyncContext asyncContext) {
        if (data.readableBytes() > 0) {
            data.retain();
            try {
                asyncContext.requestBody.handOff(data, error -> {
                    data.release();
                    if (error != null) {
                        asyncContext.onCancelled(false);
                    }
                });
            } catch (Exception e) {
                data.release();
                if (e instanceof MuException) {
                    MuResponse resp = asyncContext.response;
                    if (!resp.hasStartedSendingData()) {
                        resp.status(413);
                        resp.contentType(ContentTypes.TEXT_PLAIN_UTF8);
                        resp.headers().set(HeaderNames.CONNECTION, HeaderValues.CLOSE);
                        resp.write("413 Payload Too Large");
                    } else {
                        asyncContext.onCancelled(true);
                    }
                }
            }
        }
    }

    void onHeaders(DoneCallback addedToExecutorCallback, AsyncContext muCtx, Headers headers) {

        NettyRequestAdapter request = (NettyRequestAdapter) muCtx.request;
        if (headers.hasBody()) {
            // There will be a request body, so set the streams
            GrowableByteBufferInputStream requestBodyStream = new GrowableByteBufferInputStream(settings.requestReadTimeoutMillis, settings.maxRequestSize);
            request.inputStream(requestBodyStream);
            muCtx.requestBody = requestBodyStream;
        }
        request.nettyAsyncContext = muCtx;
        try {
            executor.execute(() -> {


                boolean error = false;
                MuResponse response = muCtx.response;
                try {
                    addedToExecutorCallback.onComplete(null);

                    boolean handled = false;
                    for (MuHandler muHandler : muHandlers) {
                        handled = muHandler.handle(muCtx.request, response);
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
                    request.clean();
                    if (error || !request.isAsync()) {
                        try {
                            muCtx.complete(error);
                        } catch (Throwable e) {
                            log.info("Error while completing request", e);
                        }
                    }
                }
            });
        } catch (Exception e) {
            try {
                addedToExecutorCallback.onComplete(e);
            } catch (Exception ignored) { }
        }
    }


    static boolean dealWithUnhandledException(MuRequest request, MuResponse response, Throwable ex) {
        boolean forceDisconnect = response instanceof Http1Response;

        if (response.hasStartedSendingData()) {
            if (((NettyResponseAdaptor)response).clientDisconnected()) {
                log.debug("Client disconnected before " + request + " was complete");
            } else {
                log.info("Unhandled error from handler for " + request + " (note that a " + response.status() +
                    " was already sent to the client before the error occurred and so the client may receive an incomplete response)", ex);
            }
        } else {
            WebApplicationException wae;
            if (ex instanceof WebApplicationException) {
                forceDisconnect = false;
                wae = (WebApplicationException) ex;
            } else {
                String errorID = "ERR-" + UUID.randomUUID().toString();
                log.info("Sending a 500 to the client with ErrorID=" + errorID + " for " + request, ex);
                wae = new InternalServerErrorException("Oops! An unexpected error occurred. The ErrorID=" + errorID);
            }
            Response exResp = wae.getResponse();
            if (exResp == null) {
                exResp = Response.serverError().build();
            }

            response.status(exResp.getStatus());
            if (forceDisconnect) {
                response.headers().set(HeaderNames.CONNECTION, HeaderValues.CLOSE);
            }
            response.contentType(ContentTypes.TEXT_HTML_UTF8);
            String message = wae.getMessage();
            message = exceptionMessageMap.getOrDefault(message, message);
            response.write("<h1>" + exResp.getStatus() + " " + exResp.getStatusInfo().getReasonPhrase() + "</h1><p>" +
                Mutils.htmlEncode(message) + "</p>");
        }
        return forceDisconnect;
    }

    void onRequestComplete(AsyncContext ctx) {
        try {
            GrowableByteBufferInputStream inputBuffer = ctx.requestBody;
            if (inputBuffer != null) {
                inputBuffer.close();
            }
        } catch (Exception e) {
            log.info("Error while cleaning up request. It may mean the client did not receive the full response for " + ctx.request, e);
        }
    }

}
