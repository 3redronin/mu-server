package io.muserver.rest;


import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.sse.OutboundSseEvent;
import org.junit.jupiter.api.Test;

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
        assertThat(event.getGenericType(), equalTo(String.class));
        assertThat(event.getMediaType(), is(MediaType.APPLICATION_SVG_XML_TYPE));
        assertThat(event.getComment(), is("A comment"));
        assertThat(event.getReconnectDelay(), is(1000L));
        assertThat(event.isReconnectDelaySet(), is(true));
        assertThat(event.getName(), is("Event name"));
        assertThat(event.getType(), equalTo(String.class));
        assertThat(event.getData(), is("Not ignored"));
    }

    @Test
    public void replacingGenericDataKeepsRawAndGenericTypesConsistent() {
        GenericType<java.util.List<String>> genericType = new GenericType<java.util.List<String>>() {
        };
        OutboundSseEvent event = new JaxSseImpl().newEventBuilder()
            .data(String.class, "old")
            .data(genericType, java.util.Collections.singletonList("new"))
            .build();

        assertThat(event.getType(), equalTo(java.util.List.class));
        assertThat(event.getGenericType(), equalTo(genericType.getType()));
    }

}
