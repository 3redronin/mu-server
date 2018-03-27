package io.muserver.openapi;

import org.junit.Test;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.net.URI;

import static io.muserver.openapi.ContactObjectBuilder.contactObject;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

public class ContactObjectTest {
    private final Writer writer = new StringWriter();
    private final ContactObjectBuilder donFlowmonigal = contactObject().withName("Don Flowmonigal").withEmail("don@example.org").withUrl(URI.create("http://example.org"));

    @Test
    public void canCreate() throws IOException {
        donFlowmonigal.build().writeJson(writer);
        assertThat(writer.toString(), equalTo("{\"name\":\"Don Flowmonigal\",\"url\":\"http://example.org\",\"email\":\"don@example.org\"}"));
    }

    @Test
    public void allOptional() throws IOException {
        contactObject().build().writeJson(writer);
        assertThat(writer.toString(), equalTo("{}"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void emailMustBeAnEmail() {
        donFlowmonigal.withEmail("don-at-example.org").build();
    }

}