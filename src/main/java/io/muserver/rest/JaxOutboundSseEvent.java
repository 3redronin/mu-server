package io.muserver.rest;

import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.SseEvent;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Type;

class JaxOutboundSseEvent implements OutboundSseEvent {
    private final @Nullable String id;
    private final @Nullable String name;
    private final long milliseconds;
    private final MediaType mediaType;
    private final @Nullable String comment;
    private final @Nullable Class type;
    private final @Nullable Object data;
    private final @Nullable GenericType genericType;

    JaxOutboundSseEvent(@Nullable String id, @Nullable String name, long milliseconds, MediaType mediaType, @Nullable String comment, @Nullable Class type, @Nullable Object data, @Nullable GenericType genericType) {
        this.id = id;
        this.name = name;
        this.milliseconds = milliseconds;
        this.mediaType = mediaType;
        this.comment = comment;
        this.type = type;
        this.data = data;
        this.genericType = genericType;
    }

    @Override
    public @Nullable Class<?> getType() {
        return type;
    }

    @Override
    public @Nullable Type getGenericType() {
        return genericType == null ? null : genericType.getType();
    }

    @Override
    public MediaType getMediaType() {
        return mediaType;
    }

    @Override
    public @Nullable Object getData() {
        return data;
    }

    @Override
    public @Nullable String getId() {
        return id;
    }

    @Override
    public @Nullable String getName() {
        return name;
    }

    @Override
    public @Nullable String getComment() {
        return comment;
    }

    @Override
    public long getReconnectDelay() {
        return milliseconds;
    }

    @Override
    public boolean isReconnectDelaySet() {
        return milliseconds != SseEvent.RECONNECT_NOT_SET;
    }
}
