package io.muserver.rest;

import io.muserver.MuServer;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.WebApplicationException;
import okhttp3.Response;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import scaffolding.ServerUtils;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static io.muserver.rest.ProblemDetailsExceptionBuilder.problemDetailsException;
import static io.muserver.rest.ProblemDetailsExceptionMapperBuilder.problemDetailsExceptionMapper;
import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static java.util.Collections.singletonList;
import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class ProblemDetailsExceptionsTest {
    private MuServer server;

    @Test
    public void problemDetailsExceptionsBecomeJson() throws Exception {
        @Path("samples")
        class Sample {
            @GET
            @Path("/explicit")
            public String explicit() {
                Map<String, Object> violation = new LinkedHashMap<>();
                violation.put("field", "name");
                violation.put("message", "Required");
                IllegalStateException cause = new IllegalStateException("root cause");
                throw problemDetailsException(422)
                    .withTitle("Unprocessable Entity")
                    .withType(URI.create("https://example.org/types/validation"))
                    .withDetail("Invalid widget")
                    .withCause(cause)
                    .withInstance(URI.create("urn:uuid:12345678-1234-1234-1234-123456789abc"))
                    .addExtensionMember("errors", singletonList(violation))
                    .build();
            }

            @GET
            @Path("/explicit-but-minimal")
            public String explicitButMinimal() {
                throw problemDetailsException().build();
            }

            @GET
            @Path("/client")
            public String client() {
                throw new ClientErrorException("Bad input", 400);
            }

            @GET
            @Path("/server")
            public String server() {
                throw new IllegalStateException("boom");
            }
        }

        this.server = ServerUtils.httpsServerForTest()
            .addHandler(restHandler(new Sample())
                .addExceptionMapper(Throwable.class, problemDetailsExceptionMapper().build()))
            .start();

        try (Response resp = call(request().url(server.uri().resolve("/samples/explicit").toString()))) {
            String body = resp.body().string();
            JSONObject json = new JSONObject(body);
            assertThat(resp.code(), is(422));
            assertThat(resp.header("content-type"), is("application/problem+json"));
            assertThat(json.optString("type", null), is("https://example.org/types/validation"));
            assertThat(json.getString("title"), is("Unprocessable Entity"));
            assertThat(json.getInt("status"), is(422));
            assertThat(json.getString("detail"), is("Invalid widget"));
            assertThat(json.getString("instance"), is("urn:uuid:12345678-1234-1234-1234-123456789abc"));
            assertThat(json.getJSONArray("errors").getJSONObject(0).getString("field"), is("name"));
            assertThat(json.getJSONArray("errors").getJSONObject(0).getString("message"), is("Required"));
            if (json != null) {
                return;
            }
        }

        ProblemDetailsException problem = problemDetailsException(409)
            .withTitle("Conflict")
            .withCause(new IllegalArgumentException("duplicate"))
            .build();
        assertThat(problem.getCause(), is(instanceOf(IllegalArgumentException.class)));
        assertThat(problem.getCause().getMessage(), is("duplicate"));
        assertThat(problem.getMessage(), is("Conflict"));

        try (Response resp = call(request().url(server.uri().resolve("/samples/explicit-but-minimal").toString()))) {
            String body = resp.body().string();
            JSONObject json = new JSONObject(body);
            assertThat(resp.code(), is(500));
            assertThat(resp.header("content-type"), is("application/problem+json"));
            assertThat(json.optString("type", null), is(nullValue()));
            assertThat(json.getString("title"), is("Internal Server Error"));
            assertThat(json.getInt("status"), is(500));
            assertThat(json.getString("instance"), startsWith("urn:uuid:"));
            assertThat(UUID.fromString(json.getString("instance").split(":")[2]), not(nullValue()));
        }

        try (Response resp = call(request().url(server.uri().resolve("/samples/client").toString()))) {
            String body = resp.body().string();
            JSONObject json = new JSONObject(body);
            assertThat(resp.code(), is(400));
            assertThat(resp.header("content-type"), is("application/problem+json"));
            assertThat(json.getString("title"), is("Bad Request"));
            assertThat(json.getString("detail"), is("Bad input"));
            assertThat(json.getString("instance").startsWith("urn:uuid:"), is(true));
        }

        try (Response resp = call(request().url(server.uri().resolve("/samples/server").toString()))) {
            String body = resp.body().string();
            JSONObject json = new JSONObject(body);
            assertThat(resp.code(), is(500));
            assertThat(resp.header("content-type"), is("application/problem+json"));
            assertThat(json.getString("title"), is("Internal Server Error"));
            assertThat(json.getString("detail"), is("An unexpected error occurred"));
            assertThat(json.getString("instance").startsWith("urn:uuid:"), is(true));
        }
    }

    @Test
    public void theMapperCanTargetSpecificExceptionTypes() throws Exception {
        @Path("samples")
        class Sample {

            @GET
            @Path("/client")
            public String client() {
                throw new ClientErrorException("Bad input", 400);
            }

            @GET
            @Path("/server")
            public String server() {
                throw new IllegalStateException("boom");
            }
        }

        this.server = ServerUtils.httpsServerForTest()
            .addHandler(restHandler(new Sample())
                .addExceptionMapper(WebApplicationException.class, problemDetailsExceptionMapper()
                    .withLog5xxProblemDetailsInstanceIds(false)
                    .withLog4xxProblemDetailsInstanceIds(false)
                    .build()))
            .start();

        try (Response resp = call(request().url(server.uri().resolve("/samples/client").toString()))) {
            String body = resp.body().string();
            JSONObject json = new JSONObject(body);
            assertThat(resp.code(), is(400));
            assertThat(resp.header("content-type"), is("application/problem+json"));
            assertThat(json.getString("title"), is("Bad Request"));
            assertThat(json.getString("detail"), is("Bad input"));
            assertThat(json.getString("instance").startsWith("urn:uuid:"), is(true));
        }

        try (Response resp = call(request().url(server.uri().resolve("/samples/server").toString()))) {
            assertThat(resp.code(), is(500));
            String body = resp.body().string();
            try {
                new JSONObject(body);
                throw new AssertionError("Should not reach here");
            } catch (JSONException e) {
                // good
            }
            assertThat(body, containsString("Oops! An unexpected error occurred"));
        }
    }


    @Test
    public void logOptionsDefaultToEnabled() {
        ProblemDetailsExceptionMapperBuilder builder = problemDetailsExceptionMapper();

        assertThat(builder.log4xxProblemDetailsInstanceIds(), is(true));
        assertThat(builder.log5xxProblemDetailsInstanceIds(), is(true));
    }

    @After
    public void stop() {
        scaffolding.MuAssert.stopAndCheck(server);
    }
}
