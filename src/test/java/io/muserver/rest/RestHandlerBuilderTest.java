package io.muserver.rest;

import io.muserver.openapi.SchemaObject;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static io.muserver.openapi.SchemaObjectBuilder.schemaObject;
import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class RestHandlerBuilderTest {

    @Test
    public void canGetThings() {
        SchemaObject stringSchema = schemaObject().withDescription("Hi").build();
        RestHandlerBuilder builder = restHandler(new Object())
            .addCustomSchema(String.class, stringSchema)
            .withOpenApiJsonUrl("/openapi.json");

        assertThat(builder.openApiJsonUrl(), is("/openapi.json"));
        assertThat(builder.openApiHtmlUrl(), nullValue());

        Map<Class<?>, SchemaObject> expectedMap = new HashMap<>();
        expectedMap.put(String.class, stringSchema);
        assertThat(builder.customSchemas(), equalTo(expectedMap));
    }

}