package io.muserver.openapi;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static io.muserver.openapi.MediaTypeObjectBuilder.mediaTypeObject;
import static io.muserver.openapi.ResponsesObjectBuilder.responsesObject;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;

public class ResponsesObjectBuilderTest {

    @Test
    public void canMergeStuff() {


        Map<String, MediaTypeObject> contentA = new HashMap<>();
        contentA.put("text/plain", mediaTypeObject()
            .withExample("text example")
            .build());
        contentA.put("application/json", mediaTypeObject()
            .withExample("json example")
            .withSchema(SchemaObjectBuilder.schemaObjectFrom(String.class).build())
            .build());
        ResponseObject responseA = ResponseObjectBuilder.responseObject()
            .withDescription("This is the first description")
            .withContent(contentA)
            .build();
        Map<String, ResponseObject> statusCodesA = new HashMap<>();
        statusCodesA.put("200", responseA);
        ResponsesObject responsesA = responsesObject()
            .withHttpStatusCodes(statusCodesA)
            .build();

        Map<String, MediaTypeObject> contentB = new HashMap<>();
        contentB.put("text/plain;charset=utf-8", mediaTypeObject()
            .withExample("text example")
            .build());
        contentB.put("application/json", mediaTypeObject()
            .withExample("second json example")
            .withSchema(SchemaObjectBuilder.schemaObjectFrom(int.class).build())
            .build());
        ResponseObject responseB = ResponseObjectBuilder.responseObject()
            .withDescription("This is the second description")
            .withContent(contentB)
            .build();
        Map<String, ResponseObject> statusCodesB = new HashMap<>();
        statusCodesB.put("200", responseB);
        statusCodesB.put("2XX", responseB);
        ResponsesObject responsesB = responsesObject()
            .withHttpStatusCodes(statusCodesB)
            .withDefaultValue(responseB)
            .build();

        ResponsesObject merged = ResponsesObjectBuilder.mergeResponses(responsesA, responsesB).build();
        assertThat(merged.defaultValue.description, is("This is the second description"));
        assertThat(merged.httpStatusCodes.keySet(), containsInAnyOrder("200", "2XX"));
        assertThat(merged.httpStatusCodes.get("2XX").description, is("This is the second description"));
        ResponseObject _200 = merged.httpStatusCodes.get("200");
        assertThat(_200.description, is("This is the first description"));
        assertThat(_200.content.keySet(), containsInAnyOrder("text/plain;charset=utf-8", "text/plain", "application/json"));

    }

}