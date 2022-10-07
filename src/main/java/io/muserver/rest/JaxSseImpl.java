package io.muserver.rest;

import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseBroadcaster;

class JaxSseImpl implements Sse {

    @Override
    public OutboundSseEvent.Builder newEventBuilder() {
        return new JaxOutboundSseEventBuilder();
    }

    @Override
    public SseBroadcaster newBroadcaster() {
        return new SseBroadcasterImpl();
    }
}
