package io.muserver.rest;

import io.muserver.AsyncSsePublisher;
import io.muserver.MuResponse;

import javax.ws.rs.ext.MessageBodyWriter;
import javax.ws.rs.sse.OutboundSseEvent;
import javax.ws.rs.sse.SseEventSink;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static io.muserver.rest.JaxRSResponse.muHeadersToJaxObj;
import static java.nio.charset.StandardCharsets.UTF_8;

public class JaxSseEventSinkImpl implements SseEventSink {

    private final AsyncSsePublisher ssePublisher;
    private final MuResponse response;
    private final EntityProviders entityProviders;
    private volatile boolean isClosed = false;

    public JaxSseEventSinkImpl(AsyncSsePublisher ssePublisher, MuResponse response, EntityProviders entityProviders) {
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

        CompletionStage<?> stage = CompletableFuture.completedFuture(null);
        if (event.isReconnectDelaySet()) {
            stage = ssePublisher.setClientReconnectTime(event.getReconnectDelay(), TimeUnit.MILLISECONDS);
        }
        if (event.getComment() != null) {
            stage = ssePublisher.sendComment(event.getComment());
        }
        if (event.getData() != null) {
            MessageBodyWriter messageBodyWriter = entityProviders.selectWriter(event.getType(), event.getGenericType(),
                new Annotation[0], event.getMediaType());
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                messageBodyWriter.writeTo(event.getData(), event.getType(), event.getGenericType(), new Annotation[0],
                    event.getMediaType(), muHeadersToJaxObj(response.headers()), out);
                String data = new String(out.toByteArray(), UTF_8);
                stage = ssePublisher.send(data, event.getName(), event.getId());
            } catch (IOException e) {
                stage = new CompletableFuture<>();
                ((CompletableFuture)stage).completeExceptionally(e);
            }
        }
        return stage;
    }

    @Override
    public void close() {
        ssePublisher.close();
        isClosed = true;
    }
}
