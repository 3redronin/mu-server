package io.muserver.openapi;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringWriter;

import static io.muserver.openapi.EncodingObjectBuilder.encodingObject;
import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

public class EncodingObjectTest {

    @Test
    public void defaultsSetCorrectlyForQuery() throws IOException {
        StringWriter writer = new StringWriter();
        EncodingObject obj = encodingObject().withStyle("label").build();
        obj.writeJson(writer);
        assertThat(obj.explode(), is(false));
        assertThat(obj.allowReserved(), is(false));
        assertThat(writer.toString(), equalTo("{\"style\":\"label\"}"));
    }

    @Test
    public void explodeIsTrueForFormOrDefaultStyle() throws IOException {
        for (String style : asList("form", null)) {
            StringWriter writer = new StringWriter();
            EncodingObject obj = encodingObject().withStyle(style).build();
            obj.writeJson(writer);
            assertThat(obj.explode(), is(true));
            assertThat(writer.toString(), equalTo(style == null ? "{}" : "{\"style\":\"form\"}"));
        }
    }

}