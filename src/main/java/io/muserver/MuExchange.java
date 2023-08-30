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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

class MuExchange implements ResponseInfo {
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
    private volatile MuAsyncHandle asyncHandle;
    private final AtomicLong requestBodySize = new AtomicLong(0);
    private InputStream requestInputStream;
    private long endTime;

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
        this.data.connection.onExchangeComplete(this);
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

    public void setReadListener(RequestBodyListener listener) {
        this.requestBodyListener = listener;
        this.data.connection.readyToRead(true);
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
        return asyncHandle != null;
    }

    public AsyncHandle handleAsync() {
        if (asyncHandle == null) {
            asyncHandle = new MuAsyncHandle(this);
        }
        return asyncHandle;
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
    }

    @Override
    public void onError(Throwable t) {
    }
}