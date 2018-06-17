package io.muserver.openapi;

import org.junit.Test;

import java.io.IOException;
import java.io.StringWriter;

import static io.muserver.openapi.EncodingObjectBuilder.encodingObject;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

public class EncodingObjectTest {
    private final StringWriter writer = new StringWriter();

    @Test
    public void defaultsSetCorrectlyForQuery() throws IOException {
        EncodingObject obj = encodingObject().build();
        obj.writeJson(writer);
        assertThat(writer.toString(), equalTo("{\"explode\":false,\"allowReserved\":false}"));
    }

    @Test
    public void explodeIsTrueForForm() throws IOException {
        EncodingObject obj = encodingObject().withStyle("form").build();
        obj.writeJson(writer);
        assertThat(writer.toString(), containsString("\"explode\":true"));
    }

}