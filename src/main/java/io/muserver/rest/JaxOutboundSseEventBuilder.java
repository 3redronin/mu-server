package io.muserver.rest;

import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.SseEvent;
import org.jspecify.annotations.Nullable;

class JaxOutboundSseEventBuilder implements OutboundSseEvent.Builder {
    private @Nullable String id;
    private @Nullable String name;
    private long milliseconds = SseEvent.RECONNECT_NOT_SET;
    private MediaType mediaType = MediaType.TEXT_PLAIN_TYPE;
    private @Nullable String comment;
    private @Nullable Class type;
    private @Nullable Object data;
    private @Nullable GenericType genericType;

    @Override
    public OutboundSseEvent.Builder id(String id) {
        this.id = id;
        return this;
    }

    @Override
    public OutboundSseEvent.Builder name(String name) {
        this.name = name;
        return this;
    }

    @Override
    public OutboundSseEvent.Builder reconnectDelay(long milliseconds) {
        this.milliseconds = milliseconds >= 0 ? milliseconds : SseEvent.RECONNECT_NOT_SET;
        return this;
    }

    @Override
    public OutboundSseEvent.Builder mediaType(MediaType mediaType) {
        if (mediaType == null) throw new NullPointerException("mediaType");
        this.mediaType = mediaType;
        return this;
    }

    @Override
    public OutboundSseEvent.Builder comment(String comment) {
        this.comment = comment;
        return this;
    }

    @Override
    public OutboundSseEvent.Builder data(Class type, Object data) {
        if (type == null) throw new NullPointerException("type");
        if (data == null) throw new NullPointerException("data");
        this.type = type;
        this.data = data;
        return this;
    }

    @Override
    public OutboundSseEvent.Builder data(GenericType type, Object data) {
        if (type == null) throw new NullPointerException("type");
        if (data == null) throw new NullPointerException("data");
        this.genericType = type;
        this.data = data;
        return this;
    }

    @Override
    public OutboundSseEvent.Builder data(Object data) {
        if (data == null) throw new NullPointerException("data");
        this.data = data;
        this.type = data.getClass();
        return this;
    }

    @Override
    public OutboundSseEvent build() {
        if (data == null && comment == null) {
            throw new IllegalStateException("Either data or a comment must be set");
        }
        return new JaxOutboundSseEvent(id, name, milliseconds, mediaType, comment, type, data, genericType);
    }
}
