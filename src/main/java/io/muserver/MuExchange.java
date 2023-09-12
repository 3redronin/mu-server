package io.muserver;

import io.muserver.rest.MuRuntimeDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.CompletionHandler;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static io.muserver.Mutils.htmlEncode;

class MuExchange implements ResponseInfo, AsyncHandle {
    private static final Logger log = LoggerFactory.getLogger(MuExchange.class);

    static final Map<String, String> exceptionMessageMap = new HashMap<>();

    static {
        MuRuntimeDelegate.ensureSet();
        exceptionMessageMap.put(new NotFoundException().getMessage(), "This page is not available. Sorry about that.");
    }


    HttpExchangeState state = HttpExchangeState.IN_PROGRESS;
    final MuExchangeData data;
    final MuRequestImpl request;
    final MuResponseImpl response;
    private volatile RequestBodyListener requestBodyListener;
    private final AtomicLong requestBodySize = new AtomicLong(0);
    private InputStream requestInputStream;
    private long endTime;
    private boolean isAsync = false;
    private List<ResponseCompleteListener> completeListeners;

    MuExchange(MuExchangeData data, MuRequestImpl request, MuResponseImpl response) {
        this.data = data;
        this.request = request;
        this.response = response;
    }

    void onRequestCompleted(Headers trailers) {
        this.request.onComplete(trailers);
        if (response.responseState().endState()) onCompleted();
    }

    void onResponseCompleted(MuResponseImpl muResponse) {
        if (request.requestState().endState()) onCompleted();
    }

    private void onCompleted() {
        boolean good = response.responseState().completedSuccessfully() && request.requestState() == RequestState.COMPLETE;
        this.state = good ? HttpExchangeState.COMPLETE : HttpExchangeState.ERRORED;
        this.endTime = System.currentTimeMillis();
        if (completeListeners != null) {
            for (ResponseCompleteListener listener : completeListeners) {
                try {
                    listener.onComplete(this);
                } catch (Exception e) {
                    log.warn("ResponseCompleteListener threw exception while processing " + this, e);
                }
            }
        }
        this.data.connection.onExchangeComplete(this);
    }

    public void abort(Throwable cause) {
        if (state.endState()) {
            log.warn("Got exception after state is " + state);
            return;
        }
        if (this.requestBodyListener != null) {
            this.requestBodyListener.onError(cause);
        }
        request.abort(cause);
        response.abort(cause);
        onCompleted();
    }


    public void onMessage(ConMessage msg) {
        RequestBodyListener bodyListener = this.requestBodyListener;
        if (msg instanceof RequestBodyData rbd) {
            if (bodyListener != null) {
                try {
                    var newSize = requestBodySize.addAndGet(rbd.buffer().remaining());
                    if (newSize > data.connection.server().maxRequestSize()) {
                        bodyListener.onError(new ClientErrorException("413 Request Entity Too Large", 413));
                        if (data.server().settings.requestBodyTooLargeAction() == RequestBodyErrorAction.SEND_RESPONSE) {
                            setReadListener(new DiscardingRequestBodyListener());
                        } else {
                            request.onError();
                        }
                    } else {
                        bodyListener.onDataReceived(rbd.buffer(), error -> {
                            if (error == null) {
                                if (rbd.last()) {
                                    onRequestCompleted(MuHeaders.EMPTY);
                                    bodyListener.onComplete();
                                    // todo not read here?
                                } else {
                                    request.onRequestBodyReceived();
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

    RequestBodyListener requestBodyListener() {
        return requestBodyListener;
    }

    public InputStream requestInputStream() {
        var in = this.requestInputStream;
        if (in == null) {
            if (requestBodyListener != null || request.requestState() != RequestState.HEADERS_RECEIVED) {
                throw new IllegalStateException("Cannot use an input stream when reading already started: " + request.requestState());
            }
            var adapter = new RequestBodyListenerToInputStreamAdapter();
            this.requestInputStream = in = adapter;
            setReadListener(adapter);
        }
        return in;
    }


    public boolean isAsync() {
        return isAsync;
    }

    public AsyncHandle handleAsync() {
        isAsync = true;
        return this;
    }


    @Override
    public void setReadListener(RequestBodyListener readListener) {
        Mutils.notNull("readListener", readListener);
        if (this.requestBodyListener != null && !(readListener instanceof DiscardingRequestBodyListener)) {
            throw new IllegalStateException("Cannot set a read listener when the body is already being read");
        }
        if (request.hasBody()) {
            this.requestBodyListener = readListener;
            this.data.connection.readyToRead(true);
        } else {
            readListener.onComplete();
        }
    }

    @Override
    public void complete() {
        if (requestBodyListener == null) {
            setReadListener(new DiscardingRequestBodyListener());
        }

        // This either sends and end-of-chunks message for a chunked request that is still streaming, or sends
        // the response headers with no body if there is no body.

        var resp = response;
        try {
            // todo for chunked, could get remaining bytes and then write it with a final-chunk to reduce writes
            resp.closeStreams();
        } catch (IOException e) {
            complete(e);
            return;
        }
        if (resp.responseState() == ResponseState.STREAMING) {
            resp.setState(ResponseState.FINISHING);
            if (resp.headers().containsValue(HeaderNames.TRANSFER_ENCODING, HeaderValues.CHUNKED, true)) {
                CountDownLatch blocker = isAsync ? new CountDownLatch(1) : null;
                boolean sendTrailers = resp.trailers != null && Headtils.getParameterizedHeaderWithValues(data.requestHeaders(), HeaderNames.TE)
                    .stream().anyMatch(v -> v.value().equalsIgnoreCase("trailers"));
                if (sendTrailers) {
                    // todo: make this more efficient
                    write(StandardCharsets.US_ASCII.encode("0\r\n"), false, error -> {
                        if (error == null) {
                            var trailersBuffer = resp.headersBuffer(resp.trailers);
                            write(trailersBuffer, false, error2 -> {
                                if (error2 == null) {
                                    resp.setState(ResponseState.FINISHED);
                                } else {
                                    complete(error2);
                                }
                                if (blocker != null) blocker.countDown();
                            });
                        } else {
                            complete(error);
                            if (blocker != null) blocker.countDown();
                        }
                    });
                } else {
                    write(StandardCharsets.US_ASCII.encode("0\r\n\r\n"), false, error -> {
                        if (error == null) {
                            resp.setState(ResponseState.FINISHED);
                        } else {
                            complete(error);
                        }
                        if (blocker != null) blocker.countDown();
                    });
                }

                if (blocker != null) {
                    try {
                        if (!blocker.await(data.server().settings.responseWriteTimeoutMillis(), TimeUnit.MILLISECONDS)) {
                            complete(new TimeoutException("Timed out finishing chunked message"));
                        }
                    } catch (InterruptedException e) {
                        complete(e);
                    }
                }
            } else {
                // TODO: check bytes sent size here
                resp.setState(ResponseState.FINISHED);
            }
        } else if (resp.responseState() == ResponseState.NOTHING) {
            var declaredLength = resp.headers().getLong(HeaderNames.CONTENT_LENGTH.toString(), -1);
            int status = resp.status();
            if (declaredLength == -1 && status != 204 && status != 304 && request.method() != Method.HEAD) {
                resp.headers().set(HeaderNames.CONTENT_LENGTH, HeaderValues.ZERO);
            } else if (declaredLength > -1 && status == 204) {
                resp.headers().remove(HeaderNames.CONTENT_LENGTH);
            } else if (declaredLength > 0 && (status != 304 && request.method() != Method.HEAD)) {
                complete(new IllegalStateException("Response length was declared to be " + declaredLength + " but no response body was given"));
                return;
            }
            if (status == 429 || status == 408 || status == 413) {
                if (!resp.headers().contains(HeaderNames.CONNECTION)) {
                    resp.headers().set(HeaderNames.CONNECTION, HeaderValues.CLOSE);
                }
            }

            var blocker = isAsync ? new CountDownLatch(1) : null;
            write(null, false, error -> {
                if (error == null) {
                    resp.setState(ResponseState.FULL_SENT);
                } else {
                    complete(error);
                }
                if (blocker != null) blocker.countDown();
            });
            if (blocker != null) {
                try {
                    if (!blocker.await(data.server().settings.responseWriteTimeoutMillis(), TimeUnit.MILLISECONDS)) {
                        complete(new TimeoutException("Timed out finishing chunked message"));
                    }
                } catch (InterruptedException e) {
                    complete(e);
                }
            }
        }
    }

    @Override
    public void complete(Throwable cause) {
        if (cause == null) {
            complete();
            return;
        }
        if (state.endState()) {
            log.warn("Completion error thrown after state is " + state, cause);
            return;
        }
        if (cause instanceof UserRequestAbortException) {
            abort(cause);
            return;
        }
        if (requestBodyListener == null) {
            setReadListener(new DiscardingRequestBodyListener());
        }

        MuRequestImpl request = data.exchange.request;
        MuResponseImpl resp = data.exchange.response;
        try {
            if (this.data.connection.isOpen() && !response.hasStartedSendingData()) {
                WebApplicationException wae;
                if (cause instanceof WebApplicationException) {
                    wae = (WebApplicationException) cause;
                } else if (cause.getCause() instanceof WebApplicationException) {
                    wae = (WebApplicationException) cause.getCause();
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
                resp.status(status);
                boolean isHttp1 = data.newRequest.version() == HttpVersion.HTTP_1_1;
                MuRuntimeDelegate.writeResponseHeaders(request.uri(), exResp, resp, isHttp1);
                boolean sendBody = exResp.getStatusInfo().getFamily() != Response.Status.Family.REDIRECTION;
                if (sendBody) {
                    String message = wae.getMessage();
                    message = MuExchange.exceptionMessageMap.getOrDefault(message, message);
                    String html = "<h1>" + status + " " + exResp.getStatusInfo().getReasonPhrase() + "</h1><p>" + htmlEncode(message) + "</p>";
                    response.contentType(ContentTypes.TEXT_HTML_UTF8);
                    ByteBuffer body = StandardCharsets.UTF_8.encode(html);
                    response.headers().set(HeaderNames.CONTENT_LENGTH, body.remaining());
                    var blocker = isAsync ? null : new CompletableFuture<Void>();
                    write(body, false, error -> {
                        if (error == null) {
                            response.setState(ResponseState.FULL_SENT);
                            if (blocker != null) blocker.complete(null);
                        } else {
                            log.info("Error while sending error message to response; will abort: " + error.getMessage());
                            if (blocker != null) {
                                blocker.completeExceptionally(error);
                            } else {
                                abort(error);
                            }
                        }
                    });
                    if (blocker != null) {
                        blocker.get(data.server().settings.responseWriteTimeoutMillis(), TimeUnit.MILLISECONDS);
                    }
                }
            } else {
                log.info(cause.getClass().getName() + " while handling " + request + " - note a " + resp.status() +
                    " was already sent and the client may have received an incomplete response. Exception was " + cause.getMessage());
                abort(cause);
            }
        } catch (Exception e) {
            log.warn("Error while processing processing " + cause + " for " + request, e);
            abort(cause);
        } finally {
            if (!isAsync && !resp.responseState().endState()) {
                abort(cause);
            }
        }
    }

    @Override
    public void write(ByteBuffer data, DoneCallback callback) {
        Mutils.notNull("data", data);
        Mutils.notNull("callback", callback);
        write(data, true, callback);
    }
    public void write(ByteBuffer data, boolean encodeChunks, DoneCallback callback) {
        if (data != null && !data.hasRemaining()) {
            try {
                callback.onComplete(null); // run async?
            } catch (Exception e) {
                complete(e);
            }
            return;
        }

        var resp = response;

        boolean chunked = encodeChunks && resp.isChunked();
        int buffersToSend = (resp.hasStartedSendingData() ? 0 : 2) + (chunked ? 2 : 0) + (data != null ? 1 : 0);
        int bi = -1;
        var toSend = new ByteBuffer[buffersToSend];
        if (!resp.hasStartedSendingData()) {
            toSend[++bi] = ByteBuffer.wrap(resp.statusCode().http11ResponseLine());
            toSend[++bi] = resp.startStreaming();
        }
        if (chunked) {
            toSend[++bi] = StandardCharsets.US_ASCII.encode(Integer.toHexString(data.remaining()) + "\r\n");
        }
        if (data != null) {
            toSend[++bi] = data;
        }
        if (chunked) {
            toSend[++bi] = StandardCharsets.US_ASCII.encode("\r\n");
        }

        var sb = new StringBuilder();
        for (ByteBuffer buffer : toSend) {
            var dest = new byte[buffer.remaining()];
            buffer.asReadOnlyBuffer().get(dest);
            sb.append(new String(dest, StandardCharsets.UTF_8));
        }
        log.info(">>\n" + sb.toString().replace("\r", "\\r").replace("\n", "\\n\r\n"));

        MuHttp1Connection con = this.data.connection;
        con.scatteringWrite(toSend, 0, toSend.length, null, new CompletionHandler<>() {
            @Override
            public void completed(Long result, Object attachment) {
                MuExchange.this.data.server().stats.onBytesSent(result);
                // todo report this up the chain so stats are updated
                try {
                    boolean broke = false;
                    for (ByteBuffer byteBuffer : toSend) {
                        if (byteBuffer.hasRemaining()) {
                            con.scatteringWrite(toSend, 0, toSend.length, null, this);
                            broke = true;
                            break;
                        }
                    }
                    if (!broke) {
                        callback.onComplete(null);
                    }
                } catch (Exception e) {
                    failed(e, attachment);
                }
            }

            @Override
            public void failed(Throwable exc, Object attachment) {
                try {
                    callback.onComplete(exc); // todo check the exchange status here - should it just be closed?
                } catch (Exception e) {
                    complete(e);
                }
            }
        });
    }

    @Override
    public Future<Void> write(ByteBuffer data) {
        CompletableFuture<Void> cf = new CompletableFuture<>();
        write(data, error -> {
            if (error == null) {
                cf.complete(null);
            } else {
                cf.completeExceptionally(error);
            }
        });
        return cf;
    }

    @Override
    public void addResponseCompleteHandler(ResponseCompleteListener responseCompleteListener) {
        if (completeListeners == null) {
            completeListeners = new ArrayList<>();
        }
        completeListeners.add(responseCompleteListener);
    }

    @Override
    public void readForm(FormConsumer formConsumer) {
        MediaType bodyType = request.headers().contentType();
        var type = bodyType == null ? null : bodyType.getType().toLowerCase();
        var subtype = bodyType == null ? null : bodyType.getSubtype().toLowerCase();
        if ("application".equals(type) && "x-www-form-urlencoded".equals(subtype)) {
            var readListener = new UrlEncodedFormReader(formConsumer);
            setReadListener(readListener);
        } else if ("multipart".equals(type) && "form-data".equals(subtype)) {
            var charset = request.headers().contentCharset(true);
            var boundary = bodyType.getParameters().get("boundary");
            if (Mutils.nullOrEmpty(boundary)) throw new BadRequestException("No boundary specified in the multipart form-data");
            var readListener = new MultipartFormParser(formConsumer, data.server().settings.tempDirectory(), charset, boundary);
            setReadListener(readListener);
        } else {
            try {
                formConsumer.onReady(EmptyForm.VALUE);
            } catch (Exception e) {
                formConsumer.onError(e);
            }
        }
    }

    @Override
    public String toString() {
        return "MuExchange " + state + " - " + request + " and " + response;
    }


    @Override
    public long duration() {
        long start = request.startTime();
        long end = endTime;
        if (end == 0) end = System.currentTimeMillis();
        return end - start;
    }

    @Override
    public boolean completedSuccessfully() {
        return state == HttpExchangeState.COMPLETE; // TODO what about upgraded?
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

class RequestBodyListenerToInputStreamAdapter extends InputStream implements RequestBodyListener {

    ByteBuffer curBuffer;
    DoneCallback doneCallback;
    private boolean eos = false;
    private IOException error;
    private final Object lock = new Object();

    @Override
    public void onDataReceived(ByteBuffer buffer, DoneCallback doneCallback) throws Exception {
        synchronized (lock) {
            this.curBuffer = buffer;
            this.doneCallback = doneCallback;
            lock.notify();
        }
    }

    @Override
    public void onComplete() {
        synchronized (lock) {
            eos = true;
            lock.notify();
        }
    }

    @Override
    public void onError(Throwable t) {
        synchronized (lock) {
            if (t instanceof IOException ioe) {
                this.error = ioe;
            } else if (t instanceof UncheckedIOException uioe) {
                this.error = uioe.getCause();
            } else {
                this.error = new IOException("Error reading data", t);
            } // todo: what about interrupted ones? and timeouts?
            lock.notify();
        }
    }

    @Override
    public int read() throws IOException {
        if (eos) return -1;
        byte[] tmp = new byte[1];
        return read(tmp, 0, 1);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        synchronized (lock) {
            if (eos) return -1;
            if (error != null) throw error;
            if (curBuffer != null && curBuffer.hasRemaining()) {
                int num = Math.min(len, curBuffer.remaining());
                curBuffer.get(b, off, num);
                return num;
            } else {
                try {
                    if (doneCallback != null) {
                        doneCallback.onComplete(null);
                    }
                    if (!eos && (curBuffer == null || !curBuffer.hasRemaining())) {
                        lock.wait(); // no need for timeout as the request body listener will time out and notify
                    }
                } catch (Exception e) {
                    onError(e);
                    throw e instanceof IOException ? (IOException) e : new IOException("Error waiting for data", e);
                }
            }
        }
        return read(b, off, len);
    }
}

class DiscardingRequestBodyListener implements RequestBodyListener {
    @Override
    public void onDataReceived(ByteBuffer buffer, DoneCallback doneCallback) throws Exception {
        doneCallback.onComplete(null);
    }
    @Override
    public void onComplete() {}
    @Override
    public void onError(Throwable t) {}
}