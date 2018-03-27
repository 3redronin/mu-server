package io.muserver.openapi;

import org.junit.Test;

import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.util.Collections;

import static io.muserver.openapi.OperationObjectBuilder.operationObject;
import static io.muserver.openapi.ParameterObjectBuilder.parameterObject;
import static io.muserver.openapi.ResponseObjectBuilder.responseObject;
import static io.muserver.openapi.ResponsesObjectBuilder.responsesObject;
import static io.muserver.openapi.SchemaObjectBuilder.schemaObject;
import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

public class OperationObjectTest {

    private final ResponsesObjectBuilder responses = responsesObject().withHttpStatusCodes(
        Collections.singletonMap("200", responseObject().withDescription("Success").build()));

    @Test
    public void canGenerateJson() throws IOException {

        OperationObject operation = operationObject().withTags(asList("pets"))
            .withSummary("Find pets by ID")
            .withDescription("Returns pets based on ID")
            .withExternalDocs(new ExternalDocumentationObjectBuilder().withDescription("The docs on the web").withUrl(URI.create("http://muserver.io")).build())
            .withOperationId("some.unique.id")
            .withResponses(responses.build()).build();


        StringWriter writer = new StringWriter();
        operation.writeJson(writer);

        assertThat(writer.toString(), equalTo("{\"tags\":[\"pets\"],\"summary\":\"Find pets by ID\",\"description\":\"Returns pets based on ID\",\"externalDocs\":{\"description\":\"The docs on the web\",\"url\":\"http://muserver.io\"},\"operationId\":\"some.unique.id\",\"responses\":{\"200\":{\"description\":\"Success\"}},\"deprecated\":false}"));
    }


    @Test
    public void duplicateParametersInDifferentLocationsAllowed() {
        operationObject()
            .withResponses(responses.build())
            .withParameters(asList(
                parameterObject().withName("something").withIn("path").withSchema(schemaObject().build()).build(),
                parameterObject().withName("something").withIn("query").withSchema(schemaObject().build()).build()
            ))
            .build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void duplicateParametersWithSameLocationNotAllowed() {
        operationObject()
            .withResponses(responses.build())
            .withParameters(asList(
                parameterObject().withName("something").withIn("path").withSchema(schemaObject().build()).build(),
                parameterObject().withName("something").withIn("path").withSchema(schemaObject().build()).build()
            ))
            .build();
    }

}