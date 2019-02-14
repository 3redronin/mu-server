package io.muserver.rest;

import io.muserver.MuResponse;
import io.muserver.SsePublisher;

import javax.ws.rs.ext.MessageBodyWriter;
import javax.ws.rs.sse.OutboundSseEvent;
import javax.ws.rs.sse.SseEventSink;
import java.io.ByteArrayOutputStream;
import java.lang.annotation.Annotation;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static io.muserver.rest.JaxRSResponse.muHeadersToJaxObj;
import static java.nio.charset.StandardCharsets.UTF_8;

class JaxSseEventSinkImpl implements SseEventSink {

    private final SsePublisher ssePublisher;
    private final MuResponse response;
    private final EntityProviders entityProviders;
    private volatile boolean isClosed = false;

    public JaxSseEventSinkImpl(SsePublisher ssePublisher, MuResponse response, EntityProviders entityProviders) {
        this.ssePublisher = ssePublisher;
        this.response = response;
        this.entityProviders = entityProviders;
    }


    @Override
    public boolean isClosed() {
        return isClosed;
    }

    @Override
    public CompletionStage<?> send(OutboundSseEvent event) {

        CompletableFuture<?> stage = new CompletableFuture<>();

        try {
            if (event.isReconnectDelaySet()) {
                ssePublisher.setClientReconnectTime(event.getReconnectDelay(), TimeUnit.MILLISECONDS);
            }
            if (event.getComment() != null) {
                ssePublisher.sendComment(event.getComment());
            }
            if (event.getData() != null) {
                MessageBodyWriter messageBodyWriter = entityProviders.selectWriter(event.getType(), event.getGenericType(),
                    new Annotation[0], event.getMediaType());
                try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    messageBodyWriter.writeTo(event.getData(), event.getType(), event.getGenericType(), new Annotation[0],
                        event.getMediaType(), muHeadersToJaxObj(response.headers()), out);
                    String data = new String(out.toByteArray(), UTF_8);
                    ssePublisher.send(data, event.getName(), event.getId());
                }
            }
            stage.complete(null);
        } catch (Throwable e) {
            stage.completeExceptionally(e);
        }
        return stage;
    }

    @Override
    public void close() {
        ssePublisher.close();
        isClosed = true;
    }
}
