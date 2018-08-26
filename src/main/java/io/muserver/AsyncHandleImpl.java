package io.muserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

class AsyncHandleImpl implements AsyncHandle {
    private static final Logger log = LoggerFactory.getLogger(AsyncHandleImpl.class);

    private final ExecutorService executorService;
    private final MuRequestImpl request;
    private final MuResponseImpl response;
    private ResponseCompletedListener responseCompletedListener;
    private RequestBodyListener readListener;

    AsyncHandleImpl(ExecutorService executorService, MuRequestImpl request, MuResponseImpl response) {
        this.executorService = executorService;
        this.request = request;
        this.response = response;
    }

    @Override
    public void setReadListener(RequestBodyListener readListener) {
        this.readListener = readListener;
        request.setReadListener(readListener);
    }

    @Override
    public void complete() {
        response.complete(false);
        onResponseComplete(true);
    }

    @Override
    public void complete(Throwable throwable) {
        try {
            ClientConnection.dealWithUnhandledException(request, response, throwable);
        } finally {
            response.complete(true);
            onResponseComplete(false);
        }
    }

    @Override
    public void write(ByteBuffer data, WriteCallback callback) {
        executorService.submit(() -> {
            try {
                response.writeBytes(data);
                try {
                    callback.onSuccess();
                } catch (Exception e) {
                    log.warn("Error while running async write callback", e);
                }
            } catch (Exception e) {
                try {
                    callback.onFailure(e);
                } catch (Exception e1) {
                    log.warn("Error while running async failure callback", e);
                }
            }
        });
    }

    @Override
    public Future<Void> write(ByteBuffer data) {
        return executorService.submit(() -> {
            response.writeBytes(data);
            return null;
        });
    }

    @Override
    public void setResponseCompletedHandler(ResponseCompletedListener responseCompletedListener) {
        this.responseCompletedListener = responseCompletedListener;
    }

    void onReadComplete(Throwable ex) {
        RequestBodyListener rl = this.readListener;
        if (rl != null) {
            if (ex == null) {
                rl.onComplete();
            } else {
                rl.onError(ex);
            }
        }
    }

    private void onResponseComplete(boolean complete) {
        ResponseCompletedListener listener = this.responseCompletedListener;
        if (listener != null) {
            listener.onComplete(complete);
        }
    }
}
