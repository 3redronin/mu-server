package io.muserver.openapi;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringWriter;

import static io.muserver.openapi.ParameterObjectBuilder.parameterObject;
import static io.muserver.openapi.SchemaObjectBuilder.schemaObject;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class ParameterObjectTest {
    private final StringWriter writer = new StringWriter();
    private final ParameterObjectBuilder param = parameterObject().withName("name").withIn("query").withSchema(schemaObject().build());

    @Test
    public void defaultsSetCorrectlyForQuery() throws IOException {
        ParameterObject obj = param.build();
        assertThat(obj.explode(), is(true));
        assertThat(obj.deprecated(), is(false));
        assertThat(obj.allowEmptyValue(), is(false));
        assertThat(obj.required(), is(false));
        assertThat(obj.allowReserved(), is(false));
        obj.writeJson(writer);
        assertThat(writer.toString(), equalTo("{\"name\":\"name\",\"in\":\"query\",\"required\":false,\"schema\":{}}"));
    }

    @Test
    public void explodeIsTrueForForm() throws IOException {
        ParameterObject obj = param.withStyle("form").build();
        assertThat(obj.explode(), is(true));
        obj.writeJson(writer);
        assertThat(writer.toString(), containsString("{}"));
    }
    @Test
    public void explodeIsTrueForNullStyle() throws IOException {
        ParameterObject obj = this.param.withStyle(null).build();
        assertThat(obj.explode(), is(true));
        obj.writeJson(writer);
        assertThat(writer.toString(), containsString("{}"));
    }


    @Test
    public void requiredDefaultsToTrueIfLocationInPath() throws IOException {
        param.withIn("path").build().writeJson(writer);
        assertThat(writer.toString(), containsString("\"required\":true"));
    }

}