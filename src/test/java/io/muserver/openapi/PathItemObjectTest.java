package io.muserver.openapi;

import org.junit.Test;

import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static io.muserver.openapi.OperationObjectBuilder.operationObject;
import static io.muserver.openapi.ParameterObjectBuilder.parameterObject;
import static io.muserver.openapi.PathItemObjectBuilder.pathItemObject;
import static io.muserver.openapi.ResponseObjectBuilder.responseObject;
import static io.muserver.openapi.ResponsesObjectBuilder.responsesObject;
import static io.muserver.openapi.SchemaObjectBuilder.schemaObject;
import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

public class PathItemObjectTest {

    @Test
    public void canWriteToJSON() throws IOException {

        Map<String, OperationObject> operations = new HashMap<>();
        ResponsesObject responses = responsesObject().withHttpStatusCodes(
            Collections.singletonMap("200", responseObject().withDescription("Success").build())).build();

        operations.put("get",
            operationObject().withTags(asList("pets"))
                .withSummary("Find pets by ID")
                .withDescription("Returns pets based on ID")
                .withExternalDocs(new ExternalDocumentationObjectBuilder().withDescription("The docs on the web").withUrl(URI.create("http://muserver.io")).build())
                .withOperationId("some.unique.id")
                .withResponses(responses).build());

        operations.put("post", operationObject()
            .withParameters(
                asList(
                    parameterObject()
                        .withName("id")
                        .withIn("path")
                        .withDescription("the description")
                        .withSchema(schemaObject().build())
                        .build())
            )
            .withResponses(responses).build());

        PathItemObject pio = pathItemObject()
            .withSummary("This is the summary\nIt's \"great\".")
            .withDescription("And oh the description")
            .withOperations(operations).build();

        try (StringWriter writer = new StringWriter()) {
            pio.writeJson(writer);
            assertThat(writer.toString(), equalTo("{\"summary\":\"This is the summary\\nIt's \\\"great\\\".\",\"description\":\"And oh the description\",\"get\":{\"tags\":[\"pets\"],\"summary\":\"Find pets by ID\",\"description\":\"Returns pets based on ID\",\"externalDocs\":{\"description\":\"The docs on the web\",\"url\":\"http://muserver.io\"},\"operationId\":\"some.unique.id\",\"responses\":{\"200\":{\"description\":\"Success\"}},\"deprecated\":false},\"post\":{\"parameters\":[{\"name\":\"id\",\"in\":\"path\",\"description\":\"the description\",\"required\":true,\"deprecated\":false,\"allowEmptyValue\":false,\"explode\":false,\"allowReserved\":false,\"schema\":{}}],\"responses\":{\"200\":{\"description\":\"Success\"}},\"deprecated\":false}}"));
        }

    }

    @Test
    public void duplicateParametersInDifferentLocationsAllowed() {
        pathItemObject()
            .withParameters(asList(
                parameterObject().withName("something").withIn("path").withSchema(schemaObject().build()).build(),
                parameterObject().withName("something").withIn("query").withSchema(schemaObject().build()).build()
            ))
            .build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void duplicateParametersWithSameLocationNotAllowed() {
        pathItemObject()
            .withParameters(asList(
                parameterObject().withName("something").withIn("path").withSchema(schemaObject().build()).build(),
                parameterObject().withName("something").withIn("path").withSchema(schemaObject().build()).build()
            ))
            .build();
    }

}