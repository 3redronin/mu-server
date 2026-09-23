package io.muserver.rest;

import io.muserver.MuServer;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyReader;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.lang.annotation.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static org.junit.jupiter.api.Assertions.*;
import static scaffolding.ClientUtils.*;
import static scaffolding.ServerUtils.httpsServerForTest;

public class OpenApiParameterContextTest {
    private MuServer server;
    @AfterEach public void stop() { if (server != null) server.stop(); }

    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.PARAMETER)
    public @interface EncodedBody { }

    @Consumes("text/plain") public static class AnnotationReader implements MessageBodyReader<String> {
        public boolean isReadable(Class<?> type, Type generic, Annotation[] annotations, MediaType media) {
            return type == String.class && Arrays.stream(annotations).anyMatch(a -> a instanceof EncodedBody);
        }
        public String readFrom(Class<String> type, Type generic, Annotation[] annotations, MediaType media,
                               MultivaluedMap<String, String> headers, InputStream input) throws IOException {
            return "decoded:" + new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    @Path("/body") public static class Bodies {
        @POST @Path("custom") @Consumes("text/plain") public String custom(@EncodedBody String body) { return body; }
        @POST @Path("plain") @Consumes("text/plain") public String plain(String body) { return body; }
    }

    @Test public void readerSelectionUsesTheSameBodyAnnotationsAsRuntime() throws Exception {
        server = httpsServerForTest().addHandler(restHandler(new Bodies()).addCustomReader(new AnnotationReader())
            .withOpenApiJsonUrl("/openapi.json")).start();
        for (String path : Arrays.asList("custom", "plain")) {
            try (okhttp3.Response response = call(request(server.uri().resolve("/body/" + path))
                .post(okhttp3.RequestBody.create("hello", okhttp3.MediaType.get("text/plain"))))) {
                assertEquals(200, response.code());
                assertEquals(path.equals("custom") ? "decoded:hello" : "hello", response.body().string());
            }
        }
        JSONObject api = document();
        JSONObject custom = (JSONObject) api.query("/paths/~1body~1custom/post/requestBody/content/text~1plain/schema");
        assertTrue(custom.isEmpty(), "Annotation-selected custom readers must remain opaque: " + custom);
        assertEquals("string", api.query("/paths/~1body~1plain/post/requestBody/content/text~1plain/schema/type"));
    }

    @Path("/books") public static class Root {
        @Path("{book}") public Chapters book(@PathParam("book") String book, @MatrixParam("lang") String language,
                                            @QueryParam("edition") String edition) { return new Chapters(); }
    }
    public static class Chapters {
        @Path("chapters/{chapter}") public Leaf chapter(@PathParam("chapter") String chapter,
                                                      @MatrixParam("lang") String language) { return new Leaf(); }
    }
    public static class Leaf {
        @GET @Path("pages/{page}") public String page(@PathParam("page") String page, @MatrixParam("lang") String language) { return page; }
    }

    @Test public void customizerNamesMatchTheEmittedMatrixAliases() throws Exception {
        server = httpsServerForTest().addHandler(restHandler(new Root()).withOpenApiJsonUrl("/openapi.json")
            .addSchemaObjectCustomizer((builder, context) -> context.target() == SchemaObjectCustomizerTarget.PARAMETER
                ? builder.withTitle(context.parameterName().get()) : builder)).start();
        JSONArray parameters = parameters(document());
        Set<String> aliases = new HashSet<>();
        for (Object value : parameters) {
            JSONObject parameter = (JSONObject) value;
            assertEquals(parameter.getString("name"), parameter.getJSONObject("schema").getString("title"));
            if (parameter.getString("name").endsWith("_lang")) aliases.add(parameter.getString("name"));
        }
        assertEquals(new HashSet<>(Arrays.asList("book_lang", "chapter_lang", "page_lang")), aliases);
    }

    @Test public void locatorContextsRetainTheirDeclaringMethodAndResource() throws Exception {
        Root root = new Root();
        List<SchemaObjectCustomizerContext> contexts = new ArrayList<>();
        server = httpsServerForTest().addHandler(restHandler(root).withOpenApiJsonUrl("/openapi.json")
            .addSchemaObjectCustomizer((builder, context) -> {
                if (context.target() == SchemaObjectCustomizerTarget.PARAMETER) contexts.add(context);
                return builder;
            })).start();
        parameters(document());
        assertEquals(7, contexts.size());
        for (SchemaObjectCustomizerContext context : contexts) {
            String name = context.parameterName().get();
            // Type and query name identify owners independently of the alias regression.
            if (context.type() != String.class) fail("Unexpected parameter type");
            if (name.equals("book") || name.equals("edition")) {
                assertEquals(Root.class.getMethod("book", String.class, String.class, String.class), context.methodHandle().get());
                assertSame(root, context.resource());
            } else if (name.equals("chapter")) {
                assertEquals(Chapters.class.getMethod("chapter", String.class, String.class), context.methodHandle().get());
                assertNull(context.resource());
            } else if (name.equals("page")) {
                assertEquals(Leaf.class.getMethod("page", String.class, String.class), context.methodHandle().get());
                assertNull(context.resource());
            }
        }
    }

    private JSONArray parameters(JSONObject document) {
        JSONObject paths = document.getJSONObject("paths");
        assertEquals(1, paths.length());
        return paths.getJSONObject(paths.keys().next()).getJSONObject("get").getJSONArray("parameters");
    }
    private JSONObject document() throws Exception {
        try (okhttp3.Response response = call(request(server.uri().resolve("/openapi.json")))) {
            assertEquals(200, response.code()); return new JSONObject(response.body().string());
        }
    }
}
