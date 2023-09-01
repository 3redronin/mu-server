package io.muserver;

import io.muserver.rest.MuRuntimeDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.ClientErrorException;
import javax.ws.rs.NotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.CompletionHandler;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

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
        request.abort(cause);
        response.abort(cause);
        onCompleted();
    }

    public void onException(Throwable cause) {

        if (state.endState()) {
            log.warn("Got exception after state is " + state);
            return;
        }

        RequestBodyListener rbl = this.requestBodyListener;
        if (rbl != null && request.requestState() == RequestState.RECEIVING_BODY) {
            // todo if this callback throws an exception, is it going to go around in a loop back to here?
            rbl.onError(cause);
        } else if (request.hasBody()) {
            // discard the remaining body
            setReadListener(new DiscardingRequestBodyListener());
        } else if (!request.requestState().endState()) {
            log.error("Didn't expect a non end state on the request for " + this);
        }
        response.onException(cause);

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
        if (request.hasBody()) {
            this.requestBodyListener = readListener;
            this.data.connection.readyToRead(true);
        } else {
            readListener.onComplete();
        }
    }

    @Override
    public void complete() {
        try {
            response.end();
        } catch (IOException e) {
            complete(e);
        }
    }

    @Override
    public void complete(Throwable throwable) {
        onException(throwable);
    }

    @Override
    public void write(ByteBuffer data, DoneCallback callback) {
        Mutils.notNull("data", data);
        Mutils.notNull("callback", callback);
        write(data, true, callback);
    }
    public void write(ByteBuffer data, boolean encodeChunks, DoneCallback callback) {
        var resp = response;

        boolean chunked = encodeChunks && resp.isChunked();
        int buffersToSend = (resp.hasStartedSendingData() ? 0 : 1) + (chunked ? 2 : 0) + (data != null ? 1 : 0);
        int bi = -1;
        var toSend = new ByteBuffer[buffersToSend];
        if (!resp.hasStartedSendingData()) {
            toSend[++bi] = resp.startStreaming();
        }
        if (chunked) {
            toSend[++bi] = StandardCharsets.US_ASCII.encode(Integer.toHexString(data.remaining()) + "\r\n");
        }
        toSend[++bi] = data;
        if (chunked) {
            toSend[++bi] = StandardCharsets.US_ASCII.encode("\r\n");
        }

        var sb = new StringBuilder();
        for (ByteBuffer buffer : toSend) {
            sb.append(new String(buffer.array(), buffer.position(), buffer.limit()));
        }
        log.info(">>\n" + sb.toString().replace("\r", "\\r").replace("\n", "\\n\r\n"));

        MuHttp1Connection con = this.data.connection;
        CompletionHandler<Long, Object> writeHandler = new CompletionHandler<>() {
            @Override
            public void completed(Long result, Object attachment) {
                MuExchange.this.data.server().stats.onBytesSent(result);
                // todo report this up the chain so stats are updated
                try {
                    boolean broke = false;
                    for (ByteBuffer byteBuffer : toSend) {
                        if (byteBuffer.hasRemaining()) {
                            con.scatteringWrite(toSend, 0, toSend.length, MuExchange.this.data.server().settings.responseWriteTimeoutMillis(), TimeUnit.MILLISECONDS, null, this);
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
        };
        con.scatteringWrite(toSend, 0, toSend.length, this.data.server().settings.responseWriteTimeoutMillis(), TimeUnit.MILLISECONDS, null, writeHandler);

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
            lock.notify();
            eos = true;
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
                    lock.wait(); // no need for timeout as the request body listener will time out and notify
                } catch (Exception e) {
                    onError(e);
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
    public void onComplete() {
        System.out.println("complete");
    }

    @Override
    public void onError(Throwable t) {
        System.out.println("err " + t);
    }



}