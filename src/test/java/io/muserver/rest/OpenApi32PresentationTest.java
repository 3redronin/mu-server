package io.muserver.rest;

import io.muserver.openapi.*;
import org.junit.Test;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;
import static io.muserver.openapi.SchemaObjectBuilder.schemaObject;
import static org.junit.Assert.*;

public class OpenApi32PresentationTest {
    @Test public void effectiveParametersAndExamplePrecedenceReachExecutableCurlArguments() throws Exception {
        ParameterObject inherited = parameter("inherited", "from path");
        ParameterObject overridden = parameter("q", "wrong path value");
        ParameterObject chosen = parameter("q", "wrong schema value").toBuilder().withExamples(Map.of("chosen",
            ExampleObjectBuilder.exampleObject().withDataValue("O'Reilly & sons").build())).build();
        PathItemObject path = PathItemObjectBuilder.pathItemObject().withParameters(Arrays.asList(inherited, overridden))
            .withOperations(Map.of("get", OperationObjectBuilder.operationObject().withOperationId("test")
                .withParameters(Collections.singletonList(chosen)).build())).build();
        List<String> args = curlArguments(render(path, null));
        assertEquals(Arrays.asList("-is", "-X", "GET", "https://example.test/example?inherited=from%20path&q=O%27Reilly%20%26%20sons"), args);
    }

    @Test public void referencedSerializedBodyOverridesSchemaExampleWithoutShellExpansion() throws Exception {
        String wire = "{\"quote\":\"O'Reilly $(echo BAD)\"}";
        MediaTypeObject media = MediaTypeObjectBuilder.mediaTypeObject()
            .withSchema(schemaObject().withExample("wrong schema value").build())
            .withExamples(Map.of("wire", ExampleObjectBuilder.exampleObject().withDataValue(Map.of("quote", "parsed"))
                .withSerializedValue(wire).build())).build();
        RequestBodyObject body = RequestBodyObjectBuilder.requestBodyObject()
            .withContentOrReferences(Map.of("application/json", ReferenceOr.reference("#/components/mediaTypes/Body"))).build();
        PathItemObject path = PathItemObjectBuilder.pathItemObject().withOperations(Map.of("post",
            OperationObjectBuilder.operationObject().withOperationId("test").withRequestBody(body).build())).build();
        String html = render(path, ComponentsObjectBuilder.componentsObject().withMediaTypes(Map.of("Body", media)).build());
        List<String> args = curlArguments(html);
        assertEquals(Arrays.asList("-is", "-X", "POST", "-H", "content-type: application/json", "--data-binary", wire, "https://example.test/example"), args);
        assertTrue(html.contains("Parsed value"));
        assertTrue(html.contains("Serialized value"));
    }

    @Test public void serializedQueryExamplesAreNotEncodedTwice() throws Exception {
        ParameterObject query = parameter("q", "wrong").toBuilder().withExplode(false)
            .withExamples(Map.of("wire", ExampleObjectBuilder.exampleObject().withDataValue(List.of("one two", "three"))
                .withSerializedValue("q=one%20two,three").build())).build();
        PathItemObject path = PathItemObjectBuilder.pathItemObject().withOperations(Map.of("get",
            OperationObjectBuilder.operationObject().withOperationId("test").withParameters(List.of(query)).build())).build();
        assertEquals("https://example.test/example?q=one%20two,three", curlArguments(render(path, null)).get(3));
    }

    @Test public void mediaTypeParametersAreSingleShellArguments() throws Exception {
        String type = "text/plain;profile=\"O'Reilly\"";
        MediaTypeObject media = MediaTypeObjectBuilder.mediaTypeObject().withExample("hello").build();
        OperationObject operation = OperationObjectBuilder.operationObject().withOperationId("test")
            .withRequestBody(RequestBodyObjectBuilder.requestBodyObject().withContent(Map.of(type, media)).build())
            .withResponses(ResponsesObjectBuilder.responsesObject().withHttpStatusCodes(Map.of("200",
                ResponseObjectBuilder.responseObject().withContent(Map.of(type, media)).build())).build()).build();
        PathItemObject path = PathItemObjectBuilder.pathItemObject().withOperations(Map.of("post", operation)).build();
        assertEquals(Arrays.asList("-is", "-X", "POST", "-H", "accept: " + type, "-H", "content-type: " + type,
            "--data-binary", "hello", "https://example.test/example"), curlArguments(render(path, null)));
    }

    private static ParameterObject parameter(String name, String sample) {
        return ParameterObjectBuilder.parameterObject().withName(name).withIn("query")
            .withSchema(schemaObject().withType("string").withExample(sample).build()).build();
    }
    private static String render(PathItemObject path, ComponentsObject components) throws Exception {
        OpenAPIObject api = OpenAPIObjectBuilder.openAPIObject().withComponents(components)
            .withPaths(PathsObjectBuilder.pathsObject().withPathItemObjects(Map.of("/example", path)).build()).build();
        StringWriter output = new StringWriter();
        try (BufferedWriter writer = new BufferedWriter(output)) {
            new HtmlDocumentor(writer, api, "", URI.create("https://example.test")).writeHtml();
        }
        return output.toString();
    }
    // Execute the actual command with a local curl stub that emits each exact argument.
    private static List<String> curlArguments(String html) throws Exception {
        Matcher matcher = Pattern.compile("<code>(curl .*?)</code>", Pattern.DOTALL).matcher(html);
        assertTrue(html, matcher.find());
        String command = matcher.group(1).replace("&#x27;", "'").replace("&quot;", "\"")
            .replace("&#x2F;", "/").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
        Process process = new ProcessBuilder("bash", "-c", "curl() { printf '%s\\0' \"$@\"; }; " + command).start();
        assertTrue("curl stub timed out", process.waitFor(5, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue());
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return Arrays.asList(output.split("\u0000"));
    }
}
