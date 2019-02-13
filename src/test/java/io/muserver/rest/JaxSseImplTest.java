package io.muserver.rest;


import org.junit.Test;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.sse.OutboundSseEvent;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class JaxSseImplTest {

    @Test
    public void itCanCreateEvents() {
        OutboundSseEvent event = new JaxSseImpl().newEventBuilder()
            .comment("A comment")
            .id("123")
            .mediaType(MediaType.APPLICATION_SVG_XML_TYPE)
            .reconnectDelay(1000)
            .data("Ignored")
            .data("Not ignored")
            .name("Event name")
            .build();
        assertThat(event.getGenericType(), is(nullValue()));
        assertThat(event.getMediaType(), is(MediaType.APPLICATION_SVG_XML_TYPE));
        assertThat(event.getComment(), is("A comment"));
        assertThat(event.getReconnectDelay(), is(1000L));
        assertThat(event.isReconnectDelaySet(), is(true));
        assertThat(event.getName(), is("Event name"));
        assertThat(event.getType(), equalTo(String.class));
        assertThat(event.getData(), is("Not ignored"));
    }

}