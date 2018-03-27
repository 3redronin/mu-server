package io.muserver.openapi;

import org.junit.Test;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;

import static io.muserver.openapi.SchemaObjectBuilder.schemaObject;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;

public class SchemaObjectTest {

    @Test
    public void allValuesOptional() throws IOException {
        schemaObject().build().writeJson(new StringWriter());
    }

    @Test(expected = IllegalArgumentException.class)
    public void youCannotHaveReadAndWriteOnly() {
        schemaObject().withReadOnly(true).withWriteOnly(true).build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void ifTypeIsArrayThenItemsIsRequired() {
        schemaObject().withType("array").build();
    }

    @Test
    public void defaultsAllowed() throws IOException {
        Writer writer = new StringWriter();
        schemaObject().withType("string").withDefaultValue("blah").build().writeJson(writer);
        assertThat(writer.toString(), containsString("\"default\":\"blah\""));
    }
    @Test
    public void defaultNumbersAllowed() throws IOException {
        Writer writer = new StringWriter();
        schemaObject().withType("number").withDefaultValue(1).build().writeJson(writer);
        assertThat(writer.toString(), containsString("\"default\":1"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void defaultsMustMatchTypeForNumber() {
        schemaObject().withType("number").withDefaultValue("1").build();
    }
    @Test(expected = IllegalArgumentException.class)
    public void defaultsMustMatchTypeForBoolean() {
        schemaObject().withType("boolean").withDefaultValue("1").build();
    }
    @Test(expected = IllegalArgumentException.class)
    public void defaultsMustMatchTypeForString() {
        schemaObject().withType("string").withDefaultValue(1).build();
    }
    @Test(expected = IllegalArgumentException.class)
    public void defaultsMustMatchTypeForArray() {
        schemaObject().withType("array").withItems(schemaObject().build()).withDefaultValue("something").build();
    }

}