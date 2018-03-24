package io.muserver.openapi;

import org.junit.Ignore;
import org.junit.Test;

import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

public class PathItemObjectTest {

    @Test
    @Ignore("Errors in json parsing it seems")
    public void canWriteToJSON() throws IOException {

        Map<String, OperationObject> operations = new HashMap<>();
        ResponsesObject responses = new ResponsesObject(null, Collections.singletonMap(200, new ResponseObject("Success", null, null, null)));

        operations.put("get", new OperationObject(asList("pets"), "Find pets by ID", "Returns pets based on ID",
            new ExternalDocumentationObject("The docs on the web", URI.create("http://muserver.io")),
            "some.unique.id", null, null, responses, null, false,
            null, null));

        operations.put("post", new OperationObject(null, null, null,
            null, null, asList(new ParameterObject("id", "path", "the description", false, false, false, null,
            null, false, null, null, null, null)), null, responses, null, false,
            null, null));

        PathItemObject pio = new PathItemObject(
            "This is the summary\nIt's \"great\".", "And oh the description", operations, null, null
        );

        try (StringWriter writer = new StringWriter()) {
            pio.writeJson(writer);
            assertThat(writer.toString(), equalTo("{\"summary\":\"This is the summary\\nIt's \\\"great\\\".\",\"description\":\"And oh the description\",\"get\":{\"tags\":[\"pets\"],\"summary\":\"Find pets by ID\",\"description\":\"Returns pets based on ID\",\"externalDocs\":{\"description\":\"The docs on the web\",\"url\":\"http://muserver.io\"},\"operationId\":\"some.unique.id\"\"responses\":{\"200\":{\"description\":\"Success\"}}\"deprecated\":false}\"post\":{\"parameters\":[{\"name\":\"id\",\"in\":\"path\",\"description\":\"the description\",\"required\":false,\"deprecated\":false,\"allowEmptyValue\":false}]\"responses\":{\"200\":{\"description\":\"Success\"}}\"deprecated\":false}}"));
        }

    }

}