package io.muserver.openapi;

import io.muserver.UploadedFile;
import org.junit.Test;

import java.io.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

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

    @Test
    public void readAndWriteOnlyAreIndependentAnnotations() {
        schemaObject().withReadOnly(true).withWriteOnly(true).build();
    }

    @Test
    public void arrayItemsMayBeOmitted() {
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
    public void noDefaultsForPrimitivesIfNotSet() throws IOException {
        Writer writer = new StringWriter();
        schemaObject().withType("number").build().writeJson(writer);
        assertThat(writer.toString(), not(containsString("\"default\"")));
    }

    @Test
    public void itCanPresetbooleans() {
        SchemaObject schema = schemaObjectFrom(boolean.class).build();
        assertThat(schema.type(), equalTo("boolean"));
        assertThat(schema.format(), is(nullValue()));
        assertThat(schema.nullable(), is(false));
    }

    @Test
    public void itCanPresetBooleans() {
        SchemaObject schema = schemaObjectFrom(Boolean.class).build();
        assertThat(schema.type(), equalTo("boolean"));
        assertThat(schema.format(), is(nullValue()));
        assertThat(schema.nullable(), is(false));
    }

    @Test
    public void itCanPresetints() {
        SchemaObject schema = schemaObjectFrom(int.class).build();
        assertThat(schema.type(), equalTo("integer"));
        assertThat(schema.format(), equalTo("int32"));
        assertThat(schema.nullable(), is(false));
    }

    @Test
    public void itCanPresetIntegers() {
        SchemaObject schema = schemaObjectFrom(Integer.class).build();
        assertThat(schema.type(), equalTo("integer"));
        assertThat(schema.format(), equalTo("int32"));
        assertThat(schema.nullable(), is(false));
    }

    @Test
    public void itCanPresetlongs() {
        SchemaObject schema = schemaObjectFrom(long.class).build();
        assertThat(schema.type(), equalTo("integer"));
        assertThat(schema.format(), equalTo("int64"));
        assertThat(schema.nullable(), is(false));
    }

    @Test
    public void itCanPresetLongs() {
        SchemaObject schema = schemaObjectFrom(Long.class).build();
        assertThat(schema.type(), equalTo("integer"));
        assertThat(schema.format(), equalTo("int64"));
        assertThat(schema.nullable(), is(false));
    }

    @Test
    public void itCanPresetfloats() {
        SchemaObject schema = schemaObjectFrom(float.class).build();
        assertThat(schema.type(), equalTo("number"));
        assertThat(schema.format(), equalTo("float"));
        assertThat(schema.nullable(), is(false));
    }

    @Test
    public void itCanPresetFloats() {
        SchemaObject schema = schemaObjectFrom(Float.class).build();
        assertThat(schema.type(), equalTo("number"));
        assertThat(schema.format(), equalTo("float"));
        assertThat(schema.nullable(), is(false));
    }

    @Test
    public void itCanPresetdoubles() {
        SchemaObject schema = schemaObjectFrom(double.class).build();
        assertThat(schema.type(), equalTo("number"));
        assertThat(schema.format(), equalTo("double"));
        assertThat(schema.nullable(), is(false));
    }

    @Test
    public void itCanPresetDoubles() {
        SchemaObject schema = schemaObjectFrom(Double.class).build();
        assertThat(schema.type(), equalTo("number"));
        assertThat(schema.format(), equalTo("double"));
        assertThat(schema.nullable(), is(false));
    }

    @Test
    public void itCanPresetStrings() {
        SchemaObject schema = schemaObjectFrom(String.class).build();
        assertThat(schema.type(), equalTo("string"));
        assertThat(schema.format(), is(nullValue()));
        assertThat(schema.nullable(), is(false));
    }

    @Test
    public void itCanPresetUUIDs() {
        SchemaObject schema = schemaObjectFrom(UUID.class).build();
        assertThat(schema.type(), equalTo("string"));
        assertThat(schema.format(), equalTo("uuid"));
        assertThat(schema.pattern(), nullValue());
        assertThat(schema.nullable(), is(false));
        assertThat(schema.examples(), contains("93d35de9-0083-4765-8b60-822258e8ffad"));
    }

    @Test
    public void itCanPresetbytes() {
        SchemaObject schema = schemaObjectFrom(byte.class).build();
        assertThat(schema.type(), equalTo("integer"));
        assertThat(schema.format(), equalTo("int8"));
        assertThat(schema.nullable(), is(false));
    }

    @Test
    public void itCanPresetBytes() {
        SchemaObject schema = schemaObjectFrom(Byte.class).build();
        assertThat(schema.type(), equalTo("integer"));
        assertThat(schema.format(), equalTo("int8"));
        assertThat(schema.nullable(), is(false));
    }


    @Test
    public void itCanPresetInstants() {
        SchemaObject schema = schemaObjectFrom(Instant.class).build();
        assertThat(schema.type(), equalTo("string"));
        assertThat(schema.format(), equalTo("date-time"));
        assertThat(schema.nullable(), is(false));
        assertThat(schema.examples(), contains("2021-02-12T15:33:28Z"));
    }

    @Test
    public void itCanPresetLocalDates() {
        SchemaObject schema = schemaObjectFrom(LocalDate.class).build();
        assertThat(schema.type(), equalTo("string"));
        assertThat(schema.format(), equalTo("date"));
        assertThat(schema.nullable(), is(false));
        assertThat(schema.examples(), contains("2021-02-12"));
    }
    @Test
    public void itCanPresetYearMonth() {
        SchemaObject schema = schemaObjectFrom(YearMonth.class).build();
        assertThat(schema.type(), equalTo("string"));
        assertThat(schema.format(), nullValue());
        assertThat(schema.nullable(), is(false));
        assertThat(schema.examples(), nullValue());
    }

    @Test
    public void itCanPresetDates() {
        SchemaObject schema = schemaObjectFrom(Date.class).build();
        assertThat(schema.type(), equalTo("string"));
        assertThat(schema.format(), equalTo("date-time"));
        assertThat(schema.nullable(), is(false));
    }

    @Test
    public void itCanPresetSets() {
        SchemaObject schema = schemaObjectFrom(Set.class).build();
        assertThat(schema.type(), equalTo("array"));
        assertThat(schema.format(), is(nullValue()));
        assertThat(schema.nullable(), is(false));
        assertThat(schema.items().type(), nullValue());
    }

    @Test
    public void itCanPresetCollections() {
        SchemaObject schema = schemaObjectFrom(Collection.class).build();
        assertThat(schema.type(), equalTo("array"));
        assertThat(schema.format(), is(nullValue()));
        assertThat(schema.nullable(), is(false));
        assertThat(schema.items().type(), nullValue());
    }

    @Test
    public void itCanPresetLists() {
        SchemaObject schema = schemaObjectFrom(List.class).build();
        assertThat(schema.type(), equalTo("array"));
        assertThat(schema.format(), is(nullValue()));
        assertThat(schema.nullable(), is(false));
        assertThat(schema.items().type(), nullValue());
    }

    @Test
    public void itCanPresetArrays() {
        SchemaObject schema = schemaObjectFrom(String[].class).build();
        assertThat(schema.type(), equalTo("array"));
        assertThat(schema.format(), is(nullValue()));
        assertThat(schema.nullable(), is(false));
        assertThat(schema.items().type(), equalTo("string"));
    }

    @Test
    public void itCanPresetBinaryThings() {
        Class<?>[] clazzes = {File.class, InputStream.class, byte[].class};
        for (Class<?> clazz : clazzes) {
            SchemaObject schema = schemaObjectFrom(clazz).build();
            assertThat(clazz.getName(), schema.type(), nullValue());
            assertThat(clazz.getName(), schema.format(), nullValue());
            assertThat(clazz.getName(), schema.nullable(), nullValue());
        }
    }

    @SuppressWarnings("WeakerAccess")
    public List<String> listOfString = new ArrayList<>();

    @Test
    public void genericTypesCanBeKnown() throws NoSuchFieldException {
        SchemaObject schema = schemaObjectFrom(listOfString.getClass(), getClass().getField("listOfString").getGenericType(), false).build();
        assertThat(schema.type(), equalTo("array"));
        assertThat(schema.format(), is(nullValue()));
        assertThat(schema.nullable(), is(false));
        assertThat(schema.items().type(), equalTo("string"));
    }


    @SuppressWarnings("WeakerAccess")
    public List<UploadedFile> listOfUploadedFiles = new ArrayList<>();

    @Test
    public void genericTypesCanBeKnownForFiles() throws NoSuchFieldException {
        SchemaObject schema = schemaObjectFrom(listOfUploadedFiles.getClass(), getClass().getField("listOfUploadedFiles").getGenericType(), false).build();
        assertThat(schema.type(), equalTo("array"));
        assertThat(schema.format(), is(nullValue()));
        assertThat(schema.nullable(), is(false));
        assertThat(schema.items().type(), nullValue());
        assertThat(schema.items().format(), nullValue());
    }


    @Test
    public void defaultsAreAnnotationsForNumber() {
        schemaObject().withType("number").withDefaultValue("1").build();
    }

    @Test
    public void defaultsAreAnnotationsForBoolean() {
        schemaObject().withType("boolean").withDefaultValue("1").build();
    }

    @Test
    public void defaultsAreAnnotationsForArray() {
        schemaObject().withType("array").withItems(schemaObject().build()).withDefaultValue("something").build();
    }

    @Test
    public void enumsSupported() {
        SchemaObject enumObj = schemaObjectFrom(MyEnum.class).build();
        assertThat(enumObj.type(), equalTo("string"));
        assertThat(enumObj.format(), nullValue());
        assertThat(enumObj.enumValue(), contains(MyEnum.One, MyEnum.Two));
    }

    private enum MyEnum {
        One, Two;
    }
}