package io.muserver.rest;

import javax.ws.rs.sse.OutboundSseEvent;
import javax.ws.rs.sse.Sse;
import javax.ws.rs.sse.SseBroadcaster;

class LegacyJaxSseImpl implements Sse {

    @Override
    public OutboundSseEvent.Builder newEventBuilder() {
        return new LegacyJaxOutboundSseEventBuilder();
    }

    @Override
    public SseBroadcaster newBroadcaster() {
        return new LegacySseBroadcasterImpl();
    }
}
