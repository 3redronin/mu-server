package io.muserver.rest;

import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.sse.OutboundSseEvent;
import javax.ws.rs.sse.SseEvent;

class JaxOutboundSseEventBuilder implements OutboundSseEvent.Builder {
    private String id;
    private String name;
    private long milliseconds = SseEvent.RECONNECT_NOT_SET;
    private MediaType mediaType = MediaType.TEXT_PLAIN_TYPE;
    private String comment;
    private Class type;
    private Object data;
    private GenericType genericType;

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
