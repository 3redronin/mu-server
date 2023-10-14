package io.muserver.openapi;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.Collections;

import static io.muserver.openapi.ComponentsObjectBuilder.componentsObject;
import static io.muserver.openapi.SchemaObjectBuilder.schemaObject;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ComponentsObjectTest {

    @Test
    public void keyNamesMustBeValid() throws IOException {
        ComponentsObject obj = componentsObject()
            .withSchemas(Collections.singletonMap("A-valid_key.123_456", schemaObject().build()))
            .build();
        Writer writer = new StringWriter();
        obj.writeJson(writer);
        assertThat(writer.toString(), startsWith("{\"schemas\":{\"A-valid_key.123_456\":{"));
    }

    @Test
    public void spacesAreNotValid() {
        ComponentsObjectBuilder builder = componentsObject()
            .withSchemas(Collections.singletonMap("a bad key", schemaObject().build()));
        assertThrows(IllegalArgumentException.class, builder::build);
    }

}