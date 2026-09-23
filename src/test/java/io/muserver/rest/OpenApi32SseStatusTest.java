package io.muserver.rest;

import io.muserver.MuServer;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import java.util.*;
import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static org.junit.Assert.*;
import static scaffolding.ClientUtils.*;
import static scaffolding.ServerUtils.httpsServerForTest;

public class OpenApi32SseStatusTest {
    private MuServer server;
    @After public void stop() { if (server != null) server.stop(); }

    @Path("/status") public static class Statuses {
        @GET @Path("created") @ApiSseEvent(code="201") public void created() { }
        @GET @Path("default") @ApiSseEvent(code="default") public void fallback() { }
        @GET @Path("error") @ApiSseEvent(code="400") public void error() { }
        @GET @Path("multiple") @ApiSseEvent(code="201") @ApiSseEvent(code="400") public void multiple() { }
        @GET @Path("implicit") @Produces(MediaType.SERVER_SENT_EVENTS) public void implicit() { }
        @GET @Path("explicit") @ApiSseEvent(code="201") @ApiResponse(code="202", message="Accepted", response=String.class, contentType="text/plain") public void explicit() { }
        @GET @Path("detected-created") @Produces(MediaType.SERVER_SENT_EVENTS) @ApiResponse(code="201", message="Created") public void detectedCreated() { }
        @GET @Path("detected-default") @Produces(MediaType.SERVER_SENT_EVENTS) @ApiResponse(code="default", message="Fallback") public void detectedDefault() { }
        @GET @Path("detected-error") @Produces(MediaType.SERVER_SENT_EVENTS) @ApiResponse(code="400", message="Error") public void detectedError() { }
        @GET @Path("empty") @ApiSseEvent(code="204") public void empty() { }
        @HEAD @Path("head") @ApiSseEvent(code="201") public void head() { }
    }
    @Path("/class-status") @ApiSseEvent(code="201") public static class ClassStatus {
        @GET public void get() { }
    }

    @Test public void declarationsOwnTheirResponseCodesWithoutAnImplicitSuccess() throws Exception {
        JSONObject paths = paths();
        for (String code : Arrays.asList("201", "default", "400")) {
            String endpoint = code.equals("201") ? "created" : code.equals("400") ? "error" : "default";
            JSONObject responses = paths.getJSONObject("/status/" + endpoint).getJSONObject("get").getJSONObject("responses");
            assertEquals(endpoint, Collections.singleton(code), responses.keySet());
            assertTrue(responses.getJSONObject(code).getJSONObject("content").getJSONObject("text/event-stream").has("itemSchema"));
        }
        assertEquals(new HashSet<>(Arrays.asList("201", "400")), responses(paths, "multiple", "get").keySet());
        assertEquals(Collections.singleton("201"), paths.getJSONObject("/class-status").getJSONObject("get").getJSONObject("responses").keySet());
    }

    @Test public void implicitAndExplicitResponsesAndBodylessRulesRemainIntact() throws Exception {
        JSONObject paths = paths();
        assertEquals(Collections.singleton("200"), responses(paths, "implicit", "get").keySet());
        JSONObject explicit = responses(paths, "explicit", "get");
        assertEquals(new HashSet<>(Arrays.asList("201", "202")), explicit.keySet());
        assertEquals("Accepted", explicit.getJSONObject("202").getString("description"));
        for (String endpoint : Arrays.asList("empty", "head")) {
            String code = endpoint.equals("empty") ? "204" : "201";
            JSONObject responses = responses(paths, endpoint, endpoint.equals("head") ? "head" : "get");
            assertEquals(Collections.singleton(code), responses.keySet());
            assertFalse(responses.getJSONObject(code).has("content"));
        }
    }

    @Test public void detectedSseDoesNotInventStatusesBesideExplicitResponseAnnotations() throws Exception {
        JSONObject paths = paths();
        assertEquals(Collections.singleton("201"), responses(paths, "detected-created", "get").keySet());
        assertEquals(Collections.singleton("default"), responses(paths, "detected-default", "get").keySet());
        assertEquals(Collections.singleton("400"), responses(paths, "detected-error", "get").keySet());
    }

    private JSONObject paths() throws Exception {
        server = httpsServerForTest().addHandler(restHandler(new Statuses(), new ClassStatus()).withOpenApiJsonUrl("/openapi.json")).start();
        try (okhttp3.Response response = call(request(server.uri().resolve("/openapi.json")))) {
            assertEquals(200, response.code()); return new JSONObject(response.body().string()).getJSONObject("paths");
        }
    }
    private JSONObject responses(JSONObject paths, String endpoint, String method) {
        return paths.getJSONObject("/status/" + endpoint).getJSONObject(method).getJSONObject("responses");
    }
}
