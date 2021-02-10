package io.muserver.openapi;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static io.muserver.openapi.EncodingObjectBuilder.encodingObject;
import static io.muserver.openapi.HeaderObjectBuilder.headerObject;
import static io.muserver.openapi.LinkObjectBuilder.linkObject;
import static io.muserver.openapi.MediaTypeObjectBuilder.mediaTypeObject;
import static io.muserver.openapi.ResponseObjectBuilder.mergeResponses;
import static io.muserver.openapi.ResponseObjectBuilder.responseObject;
import static io.muserver.openapi.SchemaObjectBuilder.schemaObject;
import static java.util.Collections.singletonMap;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class ResponseObjectBuilderTest {

    @Test
    public void ifOneIsNullThenTheOtherIsUsed() {
        ResponseObject primary = responseObject()
            .withDescription("Desc")
            .withHeaders(singletonMap("x-header", headerObject().withDescription("A header").build()))
            .withContent(singletonMap("text/plain", mediaTypeObject()
                .withExample("An example")
                .withSchema(schemaObject().withDescription("scheming").build())
                .withEncoding(singletonMap("form", encodingObject().withStyle("form").build()))
                .build()))
            .withLinks(singletonMap("something", linkObject().withDescription("a link").build()))
            .build();

        ResponseObject[] mergeds = {
            mergeResponses(primary, null).build(),
            mergeResponses(null, primary).build()
        };

        for (ResponseObject merged : mergeds) {
            assertThat(merged.description, is("Desc"));
            assertThat(merged.headers.keySet(), contains("x-header"));
            assertThat(merged.headers.get("x-header").description, is("A header"));

            assertThat(merged.content.keySet(), contains("text/plain"));
            assertThat(merged.content.get("text/plain").example, is("An example"));
            assertThat(merged.content.get("text/plain").schema.description, is("scheming"));
            assertThat(merged.content.get("text/plain").encoding.get("form").style(), is("form"));

            assertThat(merged.links.keySet(), contains("something"));
            assertThat(merged.links.get("something").description, is("a link"));
        }
    }

    @Test
    public void nullMapsAreNull() {
        ResponseObject a = responseObject().withDescription("primary").build();
        ResponseObject b = responseObject().withDescription("secondary").build();
        ResponseObject merged = mergeResponses(a, b).build();
        assertThat(merged.description, is("primary"));
        assertThat(merged.content, is(nullValue()));
        assertThat(merged.links, is(nullValue()));
        assertThat(merged.headers, is(nullValue()));
    }

    @Test
    public void mapsAreMerged() {
        ResponseObject primary = responseObject()
            .withDescription("Desc")
            .withHeaders(singletonMap("x-header", headerObject().withDescription("A header").build()))
            .withContent(singletonMap("text/plain", mediaTypeObject().withExample("An example").build()))
            .withLinks(singletonMap("something", linkObject().withDescription("a link").build()))
            .build();

        Map<String, HeaderObject> secondaryHeaders = new HashMap<>();
        secondaryHeaders.put("x-header", headerObject().withDescription("Ignored header").build());
        secondaryHeaders.put("x-sec", headerObject().withDescription("second something").build());
        Map<String, MediaTypeObject> secondaryContent = new HashMap<>();
        secondaryContent.put("text/plain", mediaTypeObject().withExample("ignored").build());
        secondaryContent.put("application/json", mediaTypeObject().withExample("second example").build());
        Map<String, LinkObject> secondaryLinks = new HashMap<>();
        secondaryLinks.put("something", linkObject().withDescription("ignored").build());
        secondaryLinks.put("something-else", linkObject().withDescription("second link").build());
        ResponseObject secondary = responseObject()
            .withDescription("Desc 2")
            .withHeaders(secondaryHeaders)
            .withContent(secondaryContent)
            .withLinks(secondaryLinks)
            .build();


        ResponseObject merged = mergeResponses(primary, secondary).build();

        assertThat(merged.description, is("Desc"));
        assertThat(merged.headers.keySet(), containsInAnyOrder("x-header", "x-sec"));
        assertThat(merged.headers.get("x-header").description, is("A header"));
        assertThat(merged.headers.get("x-sec").description, is("second something"));

        assertThat(merged.content.keySet(), containsInAnyOrder("text/plain", "application/json"));
        assertThat(merged.content.get("text/plain").example, is("An example"));
        assertThat(merged.content.get("application/json").example, is("second example"));

        assertThat(merged.links.keySet(), containsInAnyOrder("something", "something-else"));
        assertThat(merged.links.get("something").description, is("a link"));
        assertThat(merged.links.get("something-else").description, is("second link"));
    }


}