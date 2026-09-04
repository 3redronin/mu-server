package io.muserver.rest;

import io.muserver.AsyncSsePublisher;
import io.muserver.Mutils;
import io.muserver.MuResponse;
import io.muserver.ResponseCompleteListener;
import jakarta.ws.rs.ServerErrorException;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.Providers;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.SseEventSink;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static io.muserver.rest.JaxRSResponse.muHeadersToJaxObj;
import static java.nio.charset.StandardCharsets.UTF_8;

class JaxSseEventSinkImpl implements SseEventSink {
    private static final Logger log = LoggerFactory.getLogger(JaxSseEventSinkImpl.class);

    private final @Nullable AsyncSsePublisher ssePublisher;
    private final @Nullable MuResponse response;
    private final @Nullable Providers providers;
    private final List<ResponseCompleteListener> responseCompleteListeners = new CopyOnWriteArrayList<>();

    public JaxSseEventSinkImpl(@Nullable AsyncSsePublisher ssePublisher, @Nullable MuResponse response, @Nullable Providers providers) {
        this.ssePublisher = ssePublisher;
        this.response = response;
        this.providers = providers;
        if (ssePublisher != null) {
            ssePublisher.setResponseCompleteHandler(info -> {
                for (ResponseCompleteListener listener : responseCompleteListeners) {
                    try {
                        listener.onComplete(info);
                    } catch (Throwable e) {
                        log.warn("Unhandled exception from SSE response completion listener", e);
                    }
                }
            });
        }
    }

    Runnable addResponseCompleteHandler(ResponseCompleteListener listener) {
        Mutils.notNull("listener", listener);
        responseCompleteListeners.add(listener);
        return () -> responseCompleteListeners.remove(listener);
    }

    @Override
    public boolean isClosed() {
        return Objects.requireNonNull(ssePublisher, "ssePublisher").isClosed();
    }

    @Override
    public CompletionStage<?> send(OutboundSseEvent event) {
        Objects.requireNonNull(event, "event");
        AsyncSsePublisher publisher = Objects.requireNonNull(ssePublisher, "ssePublisher");
        MuResponse muResponse = Objects.requireNonNull(response, "response");
        Providers providers = Objects.requireNonNull(this.providers, "providers");
        if (isClosed()) {
            throw new IllegalStateException("The SSE stream was already closed");
        }

        CompletionStage<?> stage = null;

        try {
            if (event.isReconnectDelaySet()) {
                stage = publisher.setClientReconnectTime(event.getReconnectDelay(), TimeUnit.MILLISECONDS);
            }
            if (event.getComment() != null) {
                stage = publisher.sendComment(event.getComment());
            }
            Object dataObject = event.getData();
            if (dataObject != null) {
                Class<?> dataType = Objects.requireNonNull(event.getType(), "An SSE event with data must have a raw type");
                Type genericDataType = event.getGenericType();
                if (genericDataType == null) {
                    genericDataType = dataType;
                }
                MessageBodyWriter messageBodyWriter = JaxRSProviders.requireMessageBodyWriter(
                    providers, dataType, genericDataType, JaxRSResponse.Builder.EMPTY_ANNOTATIONS, event.getMediaType());
                try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    messageBodyWriter.writeTo(dataObject, dataType, genericDataType, JaxRSResponse.Builder.EMPTY_ANNOTATIONS,
                        event.getMediaType(), muHeadersToJaxObj(muResponse.headers()), out);
                    String data = new String(out.toByteArray(), UTF_8);
                    stage = publisher.send(data, event.getName(), event.getId());
                }
            }
            if (stage == null) {
                throw new IllegalArgumentException("The event had nothing to send");
            }
        } catch (Throwable e) {
            if (e instanceof ServerErrorException) {
                log.warn("Server error while writing data to SSE stream", e);
            }
            CompletableFuture<?> f = new CompletableFuture<>();
            f.completeExceptionally(e);
            stage = f;
        }
        return stage;
    }

    @Override
    public void close() {
        Objects.requireNonNull(ssePublisher, "ssePublisher").close();
    }
}
