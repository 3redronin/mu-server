package io.muserver.openapi;

import org.junit.Test;

import java.io.IOException;
import java.io.StringWriter;

import static io.muserver.openapi.ParameterObjectBuilder.parameterObject;
import static io.muserver.openapi.SchemaObjectBuilder.schemaObject;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

public class ParameterObjectTest {
    private final StringWriter writer = new StringWriter();
    private final ParameterObjectBuilder param = parameterObject().withName("name").withIn("query").withSchema(schemaObject().build());

    @Test
    public void defaultsSetCorrectlyForQuery() throws IOException {
        param.build().writeJson(writer);
        assertThat(writer.toString(), equalTo("{\"name\":\"name\",\"in\":\"query\",\"required\":false,\"deprecated\":false,\"allowEmptyValue\":false,\"explode\":false,\"allowReserved\":false,\"schema\":{}}"));
    }

    @Test
    public void explodeIsTrueForForm() throws IOException {
        param.withStyle("form").build().writeJson(writer);
        assertThat(writer.toString(), containsString("\"explode\":true"));
    }

    @Test
    public void requiredDefaultsToTrueIfLocationInPath() throws IOException {
        param.withIn("path").build().writeJson(writer);
        assertThat(writer.toString(), containsString("\"required\":true"));
    }

}