package io.muserver;

import io.muserver.rest.MuRuntimeDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.ClientErrorException;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static io.muserver.Mutils.htmlEncode;

class MuExchange {
    private static final Logger log = LoggerFactory.getLogger(MuExchange.class);

    private static final Map<String, String> exceptionMessageMap = new HashMap<>();

    static {
        MuRuntimeDelegate.ensureSet();
        exceptionMessageMap.put(new NotFoundException().getMessage(), "This page is not available. Sorry about that.");
    }


    HttpExchangeState state = HttpExchangeState.IN_PROGRESS;
    final MuExchangeData data;
    final MuRequestImpl request;
    final MuResponseImpl response;
    private volatile RequestBodyListener requestBodyListener;
    private volatile MuAsyncHandle asyncHandle;
    private final AtomicLong requestBodySize = new AtomicLong(0);

    MuExchange(MuExchangeData data, MuRequestImpl request, MuResponseImpl response) {
        this.data = data;
        this.request = request;
        this.response = response;
    }

    private void onRequestCompleted(Headers trailers) {
        this.request.onComplete(trailers);
        if (response.responseState().endState()) onCompleted();
    }

    void onResponseCompleted() {
        if (request.requestState().endState()) onCompleted();
    }

    private void onCompleted() {
        boolean good = response.responseState().completedSuccessfully() && request.requestState() == RequestState.COMPLETE;
        this.state = good ? HttpExchangeState.COMPLETE : HttpExchangeState.ERRORED;
        this.data.connection.onExchangeComplete(this);
    }

    public boolean onException(Throwable cause) {

        if (state.endState()) {
            log.warn("Got exception after state is " + state);
            return true;
        }

        boolean streamUnrecoverable = true;
        try {

            if (!response.hasStartedSendingData()) {
                if (request.requestState() != RequestState.ERRORED) {
                    streamUnrecoverable = false;
                }
                WebApplicationException wae;
                if (cause instanceof WebApplicationException) {
                    wae = (WebApplicationException) cause;
                } else {
                    String errorID = "ERR-" + UUID.randomUUID();
                    log.info("Sending a 500 to the client with ErrorID=" + errorID + " for " + request, cause);
                    wae = new InternalServerErrorException("Oops! An unexpected error occurred. The ErrorID=" + errorID);
                }
                Response exResp = wae.getResponse();
                if (exResp == null) {
                    exResp = Response.serverError().build();
                }
                int status = exResp.getStatus();
                if (status == 429 || status == 408 || status == 413) {
                    streamUnrecoverable = true;
                }
                response.status(status);
                boolean isHttp1 = request.protocol().equals("HTTP/1.1");
                MuRuntimeDelegate.writeResponseHeaders(request.uri(), exResp, response, isHttp1);
                if (streamUnrecoverable && isHttp1) {
                    response.headers().set(HeaderNames.CONNECTION, HeaderValues.CLOSE);
                }

                boolean sendBody = exResp.getStatusInfo().getFamily() != Response.Status.Family.REDIRECTION;
                if (sendBody) {
                    response.contentType(ContentTypes.TEXT_HTML_UTF8);
                    String message = wae.getMessage();
                    message = exceptionMessageMap.getOrDefault(message, message);
                    String html = "<h1>" + status + " " + exResp.getStatusInfo().getReasonPhrase() + "</h1><p>" + htmlEncode(message) + "</p>";
                    if (request.isAsync()) {
                        // todo: write async?
                        response.write(html);
                    } else {
                        response.write(html);
                    }
                }
                response.end();
            } else {
                log.info(cause.getClass().getName() + " while handling " + request + " - note a " + response.status() +
                    " was already sent and the client may have received an incomplete response. Exception was " + cause.getMessage());
                onCompleted();
            }
        } catch (Exception e) {
            log.warn("Error while processing processing " + cause + " for " + request, e);
            onCompleted();
        } finally {
            if (streamUnrecoverable) {
                response.onCancelled(ResponseState.ERRORED);
                request.onCancelled(ResponseState.ERRORED, cause);
                data.connection.initiateShutdown();
            }
        }
        return streamUnrecoverable;
    }


    public void onMessage(ConMessage msg) {
        RequestBodyListener bodyListener = this.requestBodyListener;
        if (msg instanceof RequestBodyData rbd) {
            if (bodyListener != null) {
                try {
                    var newSize = requestBodySize.addAndGet(rbd.buffer().remaining());
                    if (newSize > data.connection.server().maxRequestSize()) {
                        request.onError();
                        bodyListener.onError(new ClientErrorException("413 Request Entity Too Large", 413));
                    } else {
                        bodyListener.onDataReceived(rbd.buffer(), error -> {
                            if (error == null) {
                                if (rbd.last()) {
                                    onRequestCompleted(MuHeaders.EMPTY);
                                    bodyListener.onComplete();
                                    // todo not read here?
                                } else {
                                    // todo lots of small message chunks can lead to huge stacks
                                    data.connection.readyToRead(true);
                                }
                            } else {
                                // TODO also close things here?
                                bodyListener.onError(error);
                            }
                        });
                    }
                } catch (Exception e) {
                    log.warn("Exception thrown from onDataReceived handler", e);
                    // TODO: handle error
                }
            } else {
                // todo when does this happen?
                log.warn("Ignoring request body");
                data.connection.readyToRead(true);
            }
        } else if (msg instanceof EndOfChunks eoc) {
            onRequestCompleted(eoc.trailers());
            bodyListener.onComplete();
        }
    }

    public void setReadListener(RequestBodyListener listener) {
        this.requestBodyListener = listener;
        this.data.connection.readyToRead(true);
    }

    public boolean isAsync() {
        return asyncHandle != null;
    }

    public AsyncHandle handleAsync() {
        if (asyncHandle == null) {
            asyncHandle = new MuAsyncHandle(this);
        }
        return asyncHandle;
    }
}
class MuExchangeData {
    final MuHttp1Connection connection;
    final NewRequest newRequest;
    MuExchange exchange;

    MuExchangeData(MuHttp1Connection connection, NewRequest newRequest) {
        this.connection = connection;
        this.newRequest = newRequest;
    }

    Headers requestHeaders() {
        return newRequest.headers();
    }

    MuServer2 server() {
        return connection.acceptor.muServer;
    }

    ConnectionAcceptor acceptor() {
        return connection.acceptor;
    }

}