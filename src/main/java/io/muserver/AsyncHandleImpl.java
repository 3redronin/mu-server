package io.muserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.*;

class AsyncHandleImpl implements AsyncHandle {

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private static final Logger log = LoggerFactory.getLogger(AsyncHandleImpl.class);

    private final MuRequestImpl request;
    private final MuResponseImpl response;
    private ResponseCompletedListener responseCompletedListener;
    private RequestBodyListener readListener;


    AsyncHandleImpl(MuRequestImpl request, MuResponseImpl response) {
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
        complete(null);
    }

    @Override
    public void complete(Throwable throwable) {
        executorService.submit(() -> {
            boolean error = throwable != null;
            try {
                if (error) {
                    ClientConnection.dealWithUnhandledException(request, response, throwable);
                }
            } finally {
                response.complete(error);
                onResponseComplete(!error);
            }
        });

    }

    @Override
    public void write(ByteBuffer data, WriteCallback callback) {
        executorService.submit(() -> {
            try {
                response.sendBodyData(data);
                try {
                    callback.onSuccess();
                } catch (Exception e) {
                    log.warn("Error while running async write callback", e);
                }
            } catch (Exception e) {
                try {
                    callback.onFailure(e);
                } catch (Exception e1) {
                    log.warn("Error while running async failure callback", e1);
                }
            }
        });
    }

    @Override
    public Future<Void> write(ByteBuffer data) {
        return executorService.submit(() -> {
            response.sendBodyData(data);
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
