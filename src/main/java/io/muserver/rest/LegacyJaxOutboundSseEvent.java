package io.muserver.rest;

import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.sse.OutboundSseEvent;
import javax.ws.rs.sse.SseEvent;
import java.lang.reflect.Type;

class LegacyJaxOutboundSseEvent implements OutboundSseEvent {
    private final String id;
    private final String name;
    private final long milliseconds;
    private final MediaType mediaType;
    private final String comment;
    private final Class type;
    private final Object data;
    private final GenericType genericType;

    LegacyJaxOutboundSseEvent(String id, String name, long milliseconds, MediaType mediaType, String comment, Class type, Object data, GenericType genericType) {
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
    public Class<?> getType() {
        return type;
    }

    @Override
    public Type getGenericType() {
        return genericType == null ? null : genericType.getType();
    }

    @Override
    public MediaType getMediaType() {
        return mediaType;
    }

    @Override
    public Object getData() {
        return data;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getComment() {
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
