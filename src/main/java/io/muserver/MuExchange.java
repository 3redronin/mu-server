package io.muserver;

import io.muserver.rest.MuRuntimeDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.CompletionHandler;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

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
    /**
     * The size of the body written so far, excluding the transfer encoding bytes added on a body
     */
    private final AtomicLong responseBodyWritten = new AtomicLong(0);
    private InputStream requestInputStream;
    private long endTime;
    private boolean isAsync = false;
    private List<ResponseCompleteListener> completeListeners;
    private MuGZIPOutputStream gzipStream;

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
                    if (newSize > data.connection.server().maxRequestSize() && requestBodyListener != DiscardingRequestBodyListener.INSTANCE) {
                        bodyListener.onError(new ClientErrorException("413 Request Entity Too Large", 413));
                        if (data.server().settings.requestBodyTooLargeAction() == RequestBodyErrorAction.SEND_RESPONSE) {
                            if (rbd.last()) {
                                onRequestCompleted(null);
                            } else {
                                setReadListener(DiscardingRequestBodyListener.INSTANCE);
                            }
                        } else {
                            abort(new ClientErrorException("413 Request Entity Too Large", 413));
                        }
                    } else {
                        bodyListener.onDataReceived(rbd.buffer(), error -> {
                            if (error == null) {
                                if (rbd.last()) {
                                    onRequestCompleted(MuHeaders.EMPTY);
                                    bodyListener.onComplete();
                                    // todo not read here?
                                } else {
                                    // todo lots of small message chunks can lead to huge stacks
                                    request.onRequestBodyReceived();
                                    data.connection.readyToRead();
                                }
                            } else {
                                // TODO also close things here?
                                request.onError();
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
                data.connection.readyToRead();
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
            this.data.connection.readyToRead();
        } else {
            readListener.onComplete();
        }
    }

    @Override
    public void complete() {
        if (requestBodyListener == null) {
            setReadListener(DiscardingRequestBodyListener.INSTANCE);
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
            if (gzipStream == null) {
                completeStreaming(resp);
            } else {
                try {
                    gzipStream.close();
                } catch (IOException e) {
                    complete(e);
                    return;
                }
                byte[] finalBits = gzipStream.getAndClear();
                if (finalBits.length > 0) {
                    var blocker = isAsync ? null : new CompletableFuture<Void>();
                    write(ByteBuffer.wrap(finalBits), true, error -> {
                        if (error == null) {
                            completeStreaming(resp);
                            if (blocker != null) blocker.complete(null);
                        } else {
                            complete(error);
                            if (blocker != null) blocker.completeExceptionally(error);
                        }
                    });
                    if (blocker != null) {
                        try {
                            blocker.get(data.server().settings.responseWriteTimeoutMillis(), TimeUnit.MILLISECONDS);
                        } catch (InterruptedException | TimeoutException e) {
                            complete(e);
                        } catch (ExecutionException e) {
                            complete(e.getCause());
                        }
                    }
                } else {
                    completeStreaming(resp);
                }
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
            writeEmptyBody(error -> {
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
                        complete(new TimeoutException("Timed out finishing empty message"));
                    }
                } catch (InterruptedException e) {
                    complete(e);
                }
            }
        }
    }

    private void completeStreaming(MuResponseImpl resp) {
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
            long declaredLength = resp.headers().getLong(HeaderNames.CONTENT_LENGTH.toString(), -1);
            long actualLength = responseBodyWritten.get();
            if (declaredLength > actualLength) {
                var message = "Response error: body size was declared to be " + declaredLength + " bytes however only " + actualLength + " bytes were sent for " + request;
                log.warn(message);
                abort(new IllegalStateException(message));
            } else {
                resp.setState(ResponseState.FINISHED);
            }
        }
    }

    @Override
    public void sendInformationalResponse(HttpStatusCode status, DoneCallback callback) {
        if (!status.isInformational()) throw new IllegalArgumentException(status + " is not allowed in an informational response");
        if (this.response.hasStartedSendingData()) throw new IllegalStateException("Cannot send informational response after the main response has started");
        Headers headers = response.headers();
        var hasHeaders = headers.size() > 1;
        var bits = new ByteBuffer[2];
        bits[0] = ByteBuffer.wrap(status.http11ResponseLine());
        if (hasHeaders) {
            var date = headers.get(HeaderNames.DATE);
            headers.remove(HeaderNames.DATE);
            bits[1] = response.headersBuffer((MuHeaders) headers);
            headers.clear();
            if (date != null) {
                headers.set(HeaderNames.DATE, date);
            }
        } else {
            bits[1] = Mutils.toByteBuffer("\r\n");
        }
        scatteringWrite(bits, callback);
    }

    @Override
    public void complete(Throwable cause) {
        if (cause == null) {
            complete();
            return;
        }
        if (state.endState()) {
            log.warn("Completion error thrown after state is " + state + ": " + cause);
            return;
        }
        if (cause instanceof UserRequestAbortException) {
            abort(cause);
            return;
        }
        if (!request.requestState().endState() && requestBodyListener == null) {
            setReadListener(DiscardingRequestBodyListener.INSTANCE);
        }

        MuRequestImpl request = data.exchange.request;
        MuResponseImpl resp = data.exchange.response;

        UnhandledExceptionHandler handler = data.server().unhandledExceptionHandler;
        if (handler != null) {
            try {
                if (handler.handle(request, response, cause)) {
                    complete();
                    return;
                }
            } catch (Exception ex) {
                // replace the cause with whatever the exception handler threw
                cause = ex;
            }
        }

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
                boolean sendBody = resp.statusCode().canHaveEntity() && !Mutils.nullOrEmpty(wae.getMessage());
                ByteBuffer body;
                if (sendBody) {
                    String message = wae.getMessage();
                    message = MuExchange.exceptionMessageMap.getOrDefault(message, message);
                    String html = "<h1>" + status + " " + exResp.getStatusInfo().getReasonPhrase() + "</h1><p>" + htmlEncode(message) + "</p>";
                    response.contentType(ContentTypes.TEXT_HTML_UTF8);
                    body = StandardCharsets.UTF_8.encode(html);
                    response.headers().set(HeaderNames.CONTENT_LENGTH, body.remaining());
                } else {
                    body = null;
                }
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
        try {
            if (response.responseState() == ResponseState.NOTHING && response.prepareForGzip()) {
                this.gzipStream = new MuGZIPOutputStream(new ByteArrayOutputStream());
            }
            if (gzipStream != null) {
                byte[] dataBytes;
                var offset = data.position();
                var len = data.remaining();
                if (data.hasArray()) {
                    dataBytes = data.array();
                    data.position(data.limit());
                } else {
                    dataBytes = new byte[len];
                    data.get(dataBytes);
                }
                gzipStream.write(dataBytes, offset, len);
                if (gzipStream.written() > 0) {
                    write(ByteBuffer.wrap(gzipStream.getAndClear()), true, callback);
                } else {
                    callback.onComplete(null);
                }
            } else {
                write(data, true, callback);
            }
        } catch (IOException e) {
            callback.onComplete(e);
        }
    }

    public void writeEmptyBody(DoneCallback callback) {
        var bits = new ByteBuffer[2];
        bits[0] = ByteBuffer.wrap(response.statusCode().http11ResponseLine());
        bits[1] = response.headersBuffer((MuHeaders) response.headers());
        scatteringWrite(bits, callback);
    }

    public void write(ByteBuffer data, boolean isResponseEntityData, DoneCallback callback) {
        var resp = response;
        if (resp.responseState().endState()) {
            callback.onComplete(new IllegalStateException("Cannot write data when response state is " + resp.responseState()));
            return;
        }

        if (data != null && !data.hasRemaining() && resp.responseState() != ResponseState.NOTHING) {
            try {
                callback.onComplete(null); // run async?
            } catch (Exception e) {
                complete(e);
            }
            return;
        }


        boolean chunked = isResponseEntityData && resp.isChunked() && data != null;

        long toAdd = isResponseEntityData && data != null ? data.remaining() : 0;
        if (isResponseEntityData && !chunked && data != null) {
            long declaredLen = resp.headers().getLong(HeaderNames.CONTENT_LENGTH.toString(), Long.MAX_VALUE);
            long lenAfterWrite = toAdd + responseBodyWritten.get();
            if (declaredLen < lenAfterWrite) {
                IOException ex = new IOException("The declared content length for " + request.method() + " " + request.uri() + " was " + declaredLen + " bytes. The current write is being aborted and the connection is being closed because it would have resulted in " + lenAfterWrite + " bytes being sent.");
                abort(ex);
                callback.onComplete(ex);
                return;
            }
        }

        int buffersToSend = (resp.hasStartedSendingData() ? 0 : 2) + (chunked ? 2 : 0) + (data != null ? 1 : 0);
        int bi = -1;
        var toSend = new ByteBuffer[buffersToSend];
        if (!resp.hasStartedSendingData()) {
            toSend[++bi] = ByteBuffer.wrap(resp.statusCode().http11ResponseLine());
            if (data != null) {
                toSend[++bi] = resp.startStreaming();
            } else {
                toSend[++bi] = resp.headersBuffer((MuHeaders) resp.headers());
            }
        }

        boolean isHead = request.method() == Method.HEAD;
        if (chunked) {
            toSend[++bi] = isHead ? Mutils.EMPTY_BUFFER : StandardCharsets.US_ASCII.encode(Integer.toHexString(data.remaining()) + "\r\n");
        }
        if (data != null) {
            toSend[++bi] = isHead ? Mutils.EMPTY_BUFFER : data;
        }
        if (chunked) {
            toSend[++bi] = isHead ? Mutils.EMPTY_BUFFER : StandardCharsets.US_ASCII.encode("\r\n");
        }
        scatteringWrite(toSend, callback);

        // technically this is being updated too early, but doesn't matter
        responseBodyWritten.addAndGet(toAdd);
    }

    private void scatteringWrite(ByteBuffer[] toSend, DoneCallback callback) {
        logBody(toSend);

        MuHttp1Connection con = this.data.connection;
        con.scatteringWrite(toSend, 0, toSend.length, new CompletionHandler<>() {
            @Override
            public void completed(Long result, Void attachment) {
                MuExchange.this.data.server().stats.onBytesSent(result);
                // todo report this up the chain so stats are updated
                try {
                    boolean broke = false;
                    for (ByteBuffer byteBuffer : toSend) {
                        if (byteBuffer.hasRemaining()) {
                            con.scatteringWrite(toSend, 0, toSend.length, this);
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
            public void failed(Throwable exc, Void attachment) {
                try {
                    complete(exc);
                    callback.onComplete(exc); // todo check the exchange status here - should it just be closed?
                } catch (Exception e) {
                    complete(e);
                }
            }
        });
    }

    private static void logBody(ByteBuffer[] toSend) {
        var sb = new StringBuilder();
        for (ByteBuffer buffer : toSend) {
            var dest = new byte[buffer.remaining()];
            buffer.asReadOnlyBuffer().get(dest);
            String str = new String(dest, StandardCharsets.UTF_8);
            if (str.chars().anyMatch(i -> i < ' ' && i != '\n' && i != '\r')) {
                sb.setLength(0);
                sb.append("<binary data> ").append(Stream.of(toSend).map(bb -> dest.length).count()).append(" bytes");
                break;
            }
            sb.append(str);
            if (sb.length() > 1000) {
                sb.append(" ... ").append(Stream.of(toSend).map(bb -> dest.length).count());
                break;
            }
        }
        log.info(">>\n" + sb.toString().replace("\r", "\\r").replace("\n", "\\n\r\n"));
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
            if (Mutils.nullOrEmpty(boundary))
                throw new BadRequestException("No boundary specified in the multipart form-data");
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

