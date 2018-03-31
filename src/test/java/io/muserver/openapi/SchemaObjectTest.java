package io.muserver.openapi;

import org.junit.Test;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Set;

import static io.muserver.openapi.SchemaObjectBuilder.schemaObject;
import static io.muserver.openapi.SchemaObjectBuilder.schemaObjectFrom;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

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

    @Test
    public void itCanPresetbooleans() {
        SchemaObject schema = schemaObjectFrom(boolean.class).build();
        assertThat(schema.type, equalTo("boolean"));
        assertThat(schema.format, is(nullValue()));
        assertThat(schema.nullable, is(false));
    }

    @Test
    public void itCanPresetBooleans() {
        SchemaObject schema = schemaObjectFrom(Boolean.class).build();
        assertThat(schema.type, equalTo("boolean"));
        assertThat(schema.format, is(nullValue()));
        assertThat(schema.nullable, is(true));
    }

    @Test
    public void itCanPresetints() {
        SchemaObject schema = schemaObjectFrom(int.class).build();
        assertThat(schema.type, equalTo("integer"));
        assertThat(schema.format, equalTo("int32"));
        assertThat(schema.nullable, is(false));
    }

    @Test
    public void itCanPresetIntegers() {
        SchemaObject schema = schemaObjectFrom(Integer.class).build();
        assertThat(schema.type, equalTo("integer"));
        assertThat(schema.format, equalTo("int32"));
        assertThat(schema.nullable, is(true));
    }

    @Test
    public void itCanPresetlongs() {
        SchemaObject schema = schemaObjectFrom(long.class).build();
        assertThat(schema.type, equalTo("integer"));
        assertThat(schema.format, equalTo("int64"));
        assertThat(schema.nullable, is(false));
    }

    @Test
    public void itCanPresetLongs() {
        SchemaObject schema = schemaObjectFrom(Long.class).build();
        assertThat(schema.type, equalTo("integer"));
        assertThat(schema.format, equalTo("int64"));
        assertThat(schema.nullable, is(true));
    }

    @Test
    public void itCanPresetfloats() {
        SchemaObject schema = schemaObjectFrom(float.class).build();
        assertThat(schema.type, equalTo("number"));
        assertThat(schema.format, equalTo("float"));
        assertThat(schema.nullable, is(false));
    }

    @Test
    public void itCanPresetFloats() {
        SchemaObject schema = schemaObjectFrom(Float.class).build();
        assertThat(schema.type, equalTo("number"));
        assertThat(schema.format, equalTo("float"));
        assertThat(schema.nullable, is(true));
    }

    @Test
    public void itCanPresetdoubles() {
        SchemaObject schema = schemaObjectFrom(double.class).build();
        assertThat(schema.type, equalTo("number"));
        assertThat(schema.format, equalTo("double"));
        assertThat(schema.nullable, is(false));
    }

    @Test
    public void itCanPresetDoubles() {
        SchemaObject schema = schemaObjectFrom(Double.class).build();
        assertThat(schema.type, equalTo("number"));
        assertThat(schema.format, equalTo("double"));
        assertThat(schema.nullable, is(true));
    }

    @Test
    public void itCanPresetStrings() {
        SchemaObject schema = schemaObjectFrom(String.class).build();
        assertThat(schema.type, equalTo("string"));
        assertThat(schema.format, is(nullValue()));
        assertThat(schema.nullable, is(true));
    }

    @Test
    public void itCanPresetbytes() {
        SchemaObject schema = schemaObjectFrom(byte.class).build();
        assertThat(schema.type, equalTo("string"));
        assertThat(schema.format, equalTo("byte"));
        assertThat(schema.nullable, is(false));
    }

    @Test
    public void itCanPresetBytes() {
        SchemaObject schema = schemaObjectFrom(Byte.class).build();
        assertThat(schema.type, equalTo("string"));
        assertThat(schema.format, equalTo("byte"));
        assertThat(schema.nullable, is(true));
    }

    @Test
    public void itCanPresetDates() {
        SchemaObject schema = schemaObjectFrom(Date.class).build();
        assertThat(schema.type, equalTo("string"));
        assertThat(schema.format, equalTo("date-time"));
        assertThat(schema.nullable, is(true));
    }

    @Test
    public void itCanPresetSets() {
        SchemaObject schema = schemaObjectFrom(Set.class).build();
        assertThat(schema.type, equalTo("array"));
        assertThat(schema.format, is(nullValue()));
        assertThat(schema.nullable, is(true));
        assertThat(schema.items.type, equalTo("object"));
    }

    @Test
    public void itCanPresetCollections() {
        SchemaObject schema = schemaObjectFrom(Collection.class).build();
        assertThat(schema.type, equalTo("array"));
        assertThat(schema.format, is(nullValue()));
        assertThat(schema.nullable, is(true));
        assertThat(schema.items.type, equalTo("object"));
    }

    @Test
    public void itCanPresetLists() {
        SchemaObject schema = schemaObjectFrom(List.class).build();
        assertThat(schema.type, equalTo("array"));
        assertThat(schema.format, is(nullValue()));
        assertThat(schema.nullable, is(true));
        assertThat(schema.items.type, equalTo("object"));
    }

    @Test
    public void itCanPresetArrays() {
        SchemaObject schema = schemaObjectFrom(String[].class).build();
        assertThat(schema.type, equalTo("array"));
        assertThat(schema.format, is(nullValue()));
        assertThat(schema.nullable, is(true));
        assertThat(schema.items.type, equalTo("string"));
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