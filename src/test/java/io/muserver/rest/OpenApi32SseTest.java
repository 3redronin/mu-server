package io.muserver.rest;

import com.fasterxml.jackson.databind.JsonNode;
import io.muserver.MuServer;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import org.junit.After;
import org.junit.Test;

import java.util.concurrent.*;

import static io.muserver.openapi.OfflineOpenApiValidator.*;
import static io.muserver.openapi.SchemaObjectBuilder.schemaObject;
import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static org.junit.Assert.*;
import static scaffolding.ClientUtils.*;
import static scaffolding.ServerUtils.httpsServerForTest;

public class OpenApi32SseTest {
    private MuServer server;
    private final CountDownLatch release = new CountDownLatch(1);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    @After public void stop() throws Exception {
        release.countDown(); executor.shutdownNow();
        if (server != null) server.stop();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Path("/events") public class Events {
        @GET @Produces(MediaType.SERVER_SENT_EVENTS)
        @ApiSseEvent(name="price", data=Integer.class, mediaType="application/json", description="Current price")
        public void stream(@Context SseEventSink sink, @Context Sse sse) {
            sink.send(sse.newEventBuilder().name("price").id("7").reconnectDelay(1500)
                .comment("heartbeat").mediaType(MediaType.APPLICATION_JSON_TYPE).data(Integer.class, 42).build());
            executor.submit(() -> {
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                finally { sink.close(); }
            });
        }
        @GET @Path("text") @Produces(MediaType.SERVER_SENT_EVENTS)
        public void text(@Context SseEventSink sink, @Context Sse sse) {
            sink.send(sse.newEvent("hello\n世界")); sink.close();
        }
    }

    @Path("/defaults") @ApiSseEvent(name="price", data=String.class)
    public static class Defaults {
        @GET @ApiSseEvent(name="price", data=Integer.class, mediaType="application/json") @ApiSseEvent(name="notice")
        public void defaults() { }
    }

    @Test public void methodEventsOverrideClassDefaultsAndCustomizeCompleteItems() throws Exception {
        server = httpsServerForTest().addHandler(restHandler(new Defaults())
            .addSchemaObjectCustomizer((builder, context) -> {
                if (context.target() == SchemaObjectCustomizerTarget.RESPONSE_ITEM) {
                    assertEquals("text/event-stream", context.mediaType().toString());
                    if (context.eventName().orElse("").equals("price")) {
                        assertEquals(Integer.class, context.payloadType().get());
                        assertEquals(MediaType.APPLICATION_JSON_TYPE, context.payloadMediaType().get());
                    }
                    builder.withTitle(context.eventName().orElse("generic"));
                }
                return builder;
            }).withOpenApiJsonUrl("/openapi.json")).start();
        try (okhttp3.Response response = call(request(server.uri().resolve("/openapi.json")))) {
            JsonNode api = JSON.readTree(response.body().string()); document(api);
            JsonNode choices = api.at("/paths/~1defaults/get/responses/200/content/text~1event-stream/itemSchema/oneOf");
            assertEquals(2, choices.size());
            assertEquals("price", choices.get(0).get("title").asText());
            assertEquals("integer", choices.get(0).at("/properties/data/contentSchema/type").asText());
        }
    }

    @Produces("application/json") public static class IntegerJsonWriter implements jakarta.ws.rs.ext.MessageBodyWriter<Integer> {
        public boolean isWriteable(Class<?> type, java.lang.reflect.Type generic, java.lang.annotation.Annotation[] annotations, MediaType media) { return type == Integer.class; }
        public void writeTo(Integer value, Class<?> type, java.lang.reflect.Type generic, java.lang.annotation.Annotation[] annotations,
            MediaType media, jakarta.ws.rs.core.MultivaluedMap<String, Object> headers, java.io.OutputStream out) throws java.io.IOException {
            out.write(value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    @Test public void realFramesValidateAsParsedItemsAndDecodedJsonBeforeClosure() throws Exception {
        server = httpsServerForTest().addHandler(restHandler(new Events())
            .addCustomWriter(new IntegerJsonWriter())
            .addCustomSchema(Integer.class, schemaObject().withType("integer").withMinimum(0.0).build())
            .withOpenApiJsonUrl("/openapi.json").withOpenApiHtmlUrl("/docs")).start();
        JsonNode api;
        try (okhttp3.Response response = call(request(server.uri().resolve("/openapi.json")))) {
            api = JSON.readTree(response.body().string()); document(api);
        }
        JsonNode media = api.at("/paths/~1events/get/responses/200/content/text~1event-stream");
        JsonNode item = media.get("itemSchema");
        assertEquals("#/components/schemas/Integer", item.at("/properties/data/contentSchema/$ref").asText());
        try (okhttp3.Response response = call(request(server.uri().resolve("/events")))) {
            assertEquals(200, response.code());
            okio.BufferedSource source = response.body().source();
            com.fasterxml.jackson.databind.node.ObjectNode parsed = JSON.createObjectNode();
            String line;
            while ((line = source.readUtf8LineStrict()) != null) {
                if (line.isEmpty()) { if (parsed.has("data")) break; else continue; }
                if (line.startsWith(":")) continue;
                int colon = line.indexOf(':');
                if (colon < 0) continue;
                String key = line.substring(0, colon), value = line.substring(colon + 1).stripLeading();
                if (key.equals("retry")) parsed.put(key, Integer.parseInt(value)); else parsed.put(key, value);
            }
            assertEquals(1, release.getCount());
            assertEquals("price", parsed.get("event").asText());
            assertEquals("7", parsed.get("id").asText());
            accepts(item, parsed.toString(), true);
            // JSON Schema does not automatically decode contentSchema annotations.
            accepts(api.at("/components/schemas/Integer"), parsed.get("data").asText(), true);
            accepts(api.at("/components/schemas/Integer"), "-1", false);
            release.countDown();
        }
        try (okhttp3.Response response = call(request(server.uri().resolve("/events/text")))) {
            String frames = response.body().string();
            assertTrue(frames.contains("hello")); assertTrue(frames.contains("世界"));
        }
        JsonNode generic = api.at("/paths/~1events~1text/get/responses/200/content/text~1event-stream/itemSchema");
        accepts(generic, "{\"data\":\"hello\\n世界\"}", true);
        accepts(generic, "{\"data\":\"hello\",\"retry\":-1}", false);
        try (okhttp3.Response response = call(request(server.uri().resolve("/docs")))) {
            String html = response.body().string();
            assertTrue(html.contains("curl -N")); assertTrue(html.contains("Parsed stream item"));
        }
    }
}
