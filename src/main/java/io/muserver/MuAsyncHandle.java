package io.muserver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.CompletionHandler;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class MuAsyncHandle implements AsyncHandle {

    private final MuExchange exchange;

    public MuAsyncHandle(MuExchange exchange) {
        this.exchange = exchange;
    }

    @Override
    public void setReadListener(RequestBodyListener readListener) {
        exchange.data.exchange.setReadListener(readListener);
    }

    @Override
    public void complete() {
        try {
            exchange.response.end();
        } catch (IOException e) {
            throw new UncheckedIOException("Error while closing response", e);
        }
    }

    @Override
    public void complete(Throwable throwable) {
        exchange.onException(throwable);
    }

    @Override
    public void write(ByteBuffer data, DoneCallback callback) {
        var resp = exchange.response;

        boolean chunked = resp.isChunked();
        int buffersToSend = (resp.hasStartedSendingData() ? 0 : 1) + (chunked ? 2 : 0) + 1;
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

        exchange.data.connection.scatteringWrite(toSend, 0, toSend.length, 30, TimeUnit.SECONDS, null, new CompletionHandler<>() {
            @Override
            public void completed(Long result, Object attachment) {
                // todo report this up the chain so stats are updated
                try {
                    for (ByteBuffer byteBuffer : toSend) {
                        if (byteBuffer.hasRemaining()) {
                            // TODO go again
                            throw new NotImplementedException();
                        }
                    }
                    callback.onComplete(null);
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
        throw new NotImplementedException();
    }

}
