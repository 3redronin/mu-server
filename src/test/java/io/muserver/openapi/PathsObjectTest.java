package io.muserver.openapi;

import org.junit.Test;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static io.muserver.openapi.OperationObjectBuilder.operationObject;
import static io.muserver.openapi.PathItemObjectBuilder.pathItemObject;
import static io.muserver.openapi.PathsObjectBuilder.pathsObject;
import static io.muserver.openapi.ResponseObjectBuilder.responseObject;
import static io.muserver.openapi.ResponsesObjectBuilder.responsesObject;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

public class PathsObjectTest {

    private final ResponsesObjectBuilder responses = responsesObject().withHttpStatusCodes(
        Collections.singletonMap("200", responseObject().withDescription("Success").build()));

    @Test
    public void theseCanBeCreatedWithNoPaths() throws IOException {
        PathsObject obj = pathsObject().build();
        Writer writer = new StringWriter();
        obj.writeJson(writer);
        assertThat(writer.toString(), equalTo("{}"));
    }

    @Test
    public void theseCanBeCreatedWithMultiplePaths() throws IOException {
        Map<String, PathItemObject> map = new HashMap<>();
        map.put("/something", pathItemObject().build());
        map.put("/something else/yeah", pathItemObject().build());
        PathsObject obj = pathsObject()
            .withPathItemObjects(map)
            .build();
        Writer writer = new StringWriter();
        obj.writeJson(writer);
        assertThat(writer.toString(), equalTo("{\"/something\":{},\"/something else/yeah\":{}}"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void pathsMustStartWithASlash() {
        Map<String, PathItemObject> map = new HashMap<>();
        map.put("/something", pathItemObject().build());
        map.put("something else/yeah", pathItemObject().build());
        pathsObject()
            .withPathItemObjects(map)
            .build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void operationIdMustBeUniqueAcrossAllOperations() {
        Map<String, PathItemObject> map = new HashMap<>();
        map.put("/something", pathItemObject()
            .withOperations(Collections.singletonMap("get", operationObject().withOperationId("something.get").withResponses(responses.build()).build()))
            .build());
        map.put("/something else/yeah", pathItemObject()
            .withOperations(Collections.singletonMap("get", operationObject().withOperationId("something.get").withResponses(responses.build()).build()))
            .build());
        pathsObject()
            .withPathItemObjects(map)
            .build();

    }


}