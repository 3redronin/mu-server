package io.muserver.rest;

import io.muserver.MuServer;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import okhttp3.Response;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import scaffolding.ServerUtils;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
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
    public void uriParameterConversionFailuresBecomeBadRequestProblems() throws Exception {
        @Path("samples")
        class Sample {
            @GET
            public String get(@QueryParam("breed") Breed breed) {
                return breed.name();
            }

            @GET
            @Path("multiple")
            public String getMultiple(@QueryParam("breed") List<Breed> breeds) {
                return breeds.toString();
            }

            @GET
            @Path("custom")
            public String getCustom(@QueryParam("breed") CustomBreed breed) {
                return breed.name();
            }
        }

        this.server = ServerUtils.httpsServerForTest()
            .addHandler(restHandler(new Sample()))
            .start();

        try (Response resp = call(request().url(server.uri().resolve("/samples?breed=BAD_DOG").toString()))) {
            JSONObject json = new JSONObject(resp.body().string());
            assertThat(resp.code(), is(400));
            assertThat(resp.header("content-type"), is("application/problem+json"));
            assertThat(json.getString("title"), is("Bad Request"));
            assertThat(json.getInt("status"), is(400));
            assertThat(json.getString("detail"), is("Invalid value for URI parameter \"breed\"."));
            assertThat(json.getString("parameter"), is("breed"));
            assertThat(json.getString("suppliedValue"), is("BAD_DOG"));
            assertThat(json.getJSONArray("allowedValues").toList(), is(Arrays.asList("CHIHUAHUA", "YELPER")));
            assertThat(json.getString("instance"), startsWith("urn:uuid:"));
        }

        try (Response resp = call(request().url(server.uri().resolve("/samples/multiple?breed=BAD_DOG").toString()))) {
            JSONObject json = new JSONObject(resp.body().string());
            assertThat(resp.code(), is(400));
            assertThat(json.getJSONArray("allowedValues").toList(), is(Arrays.asList("CHIHUAHUA", "YELPER")));
        }

        try (Response resp = call(request().url(server.uri().resolve("/samples/custom?breed=BAD_DOG").toString()))) {
            JSONObject json = new JSONObject(resp.body().string());
            assertThat(resp.code(), is(400));
            assertThat(json.getString("parameter"), is("breed"));
            assertThat(json.getString("suppliedValue"), is("BAD_DOG"));
            assertThat(json.has("allowedValues"), is(false));
        }
    }

    private enum Breed {
        CHIHUAHUA,
        YELPER
    }

    private enum CustomBreed {
        CHIHUAHUA;

        public static CustomBreed fromString(String value) {
            if ("small".equals(value)) {
                return CHIHUAHUA;
            }
            throw new IllegalArgumentException("Invalid custom breed");
        }
    }

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
                .addExceptionMapper(WebApplicationException.class, problemDetailsExceptionMapper()
                    .withLog5xxProblemDetailsInstanceIds(false)
                    .withLog4xxProblemDetailsInstanceIds(false)
                    .build()))
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
            assertThat(json.getString("title"), is("Bad input"));
            assertThat(json.has("detail"), is(false));
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
    public void mapperGeneratesAnInstanceWhenProblemDetailsExceptionHasNone() {
        ProblemDetailsException problem = new ProblemDetailsException(
            400, "Bad Request", null, null, null, Collections.emptyMap(), null);
        ProblemDetailsExceptionMapper<ProblemDetailsException> mapper = problemDetailsExceptionMapper()
            .withLog4xxProblemDetailsInstanceIds(false)
            .build();

        jakarta.ws.rs.core.Response response = mapper.toResponse(problem);
        JSONObject json = new JSONObject((String) response.getEntity());

        assertThat(response.getStatus(), is(400));
        assertThat(json.getString("instance"), startsWith("urn:uuid:"));
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
            assertThat(json.getString("title"), is("Bad input"));
            assertThat(json.has("detail"), is(false));
            assertThat(json.getString("instance").startsWith("urn:uuid:"), is(true));
        }

        try (Response resp = call(request().url(server.uri().resolve("/samples/server").toString()))) {
            assertThat(resp.code(), is(500));
            String body = resp.body().string();
            JSONObject json = new JSONObject(body);
            assertThat(json.getString("title"), is("Internal Server Error"));
            assertThat(json.getInt("status"), is(500));
            assertThat(json.getString("detail"), is("An unexpected error occurred"));
        }
    }

    @Test
    public void defaultMapperUsedForUnhandledRuntimeExceptions() throws Exception {
        @Path("samples")
        class Sample {
            @GET
            public String get() {
                throw new IllegalStateException("Password xyz; database /private/path/customer.db");
            }
        }

        this.server = ServerUtils.httpsServerForTest()
            .addHandler(restHandler(new Sample()))
            .start();

        try (Response resp = call(request().url(server.uri().resolve("/samples").toString()))) {
            String body = resp.body().string();
            JSONObject json = new JSONObject(body);
            assertThat(resp.code(), is(500));
            assertThat(resp.header("content-type"), is("application/problem+json"));
            assertThat(resp.headers("cache-control"), is(singletonList("no-store")));
            assertThat(json.getInt("status"), is(500));
            assertThat(json.getString("title"), is("Internal Server Error"));
            assertThat(body.contains("Password xyz"), is(false));
            assertThat(body.contains("/private/path/customer.db"), is(false));
            assertThat(body.contains("IllegalStateException"), is(false));
        }
    }

    @Test
    public void acceptHeadersDoNotChangeGeneratedProblemDetailsResponse() throws Exception {
        @Path("samples")
        class Sample {
            @GET
            public String get() {
                throw new IllegalStateException("boom");
            }
        }

        this.server = ServerUtils.httpsServerForTest()
            .addHandler(restHandler(new Sample()))
            .start();

        for (String acceptHeader : Arrays.asList(
            "application/problem+json",
            "application/json",
            "*/*",
            "text/html",
            "application/problem+json;q=0"
        )) {
            try (Response resp = call(request().url(server.uri().resolve("/samples").toString())
                .header("Accept", acceptHeader))) {
                assertThat(resp.code(), is(500));
                assertThat(resp.header("content-type"), is("application/problem+json"));
                String vary = resp.header("vary");
                if (vary != null) {
                    assertThat(vary.toLowerCase(Locale.ROOT).contains("accept"), is(false));
                }
            }
        }
    }

    @Test
    public void explicitWebApplicationExceptionResponsesArePreserved() throws Exception {
        @Path("samples")
        class Sample {
            @GET
            @Path("/text")
            public String text() {
                throw new WebApplicationException(
                    jakarta.ws.rs.core.Response.status(418)
                        .entity("No coffee")
                        .type(MediaType.TEXT_PLAIN_TYPE)
                        .header("X-Test", "yes")
                        .build());
            }

            @GET
            @Path("/binary")
            public String binary() {
                throw new WebApplicationException(
                    jakarta.ws.rs.core.Response.status(201)
                        .entity(new byte[]{(byte) 0xCA, (byte) 0xFE, 0x00, 0x01})
                        .type(MediaType.APPLICATION_OCTET_STREAM_TYPE)
                        .header("X-Test", "yes")
                        .build());
            }

            @GET
            @Path("/problem")
            public String problem() {
                throw new WebApplicationException(
                    jakarta.ws.rs.core.Response.status(409)
                        .entity("{\"title\":\"Existing\"}")
                        .type(MediaType.valueOf("application/problem+json"))
                        .header("X-Test", "yes")
                        .build());
            }
        }
        this.server = ServerUtils.httpsServerForTest().addHandler(restHandler(new Sample())).start();

        try (Response resp = call(request().url(server.uri().resolve("/samples/text").toString()))) {
            assertThat(resp.code(), is(418));
            assertThat(resp.header("content-type"), is("text/plain;charset=utf-8"));
            assertThat(resp.header("X-Test"), is("yes"));
            assertThat(resp.body().string(), is("No coffee"));
        }

        try (Response resp = call(request().url(server.uri().resolve("/samples/binary").toString()))) {
            assertThat(resp.code(), is(201));
            assertThat(resp.header("content-type"), is("application/octet-stream"));
            assertThat(resp.header("X-Test"), is("yes"));
            assertThat(Arrays.equals(resp.body().bytes(), new byte[]{(byte) 0xCA, (byte) 0xFE, 0x00, 0x01}), is(true));
        }

        try (Response resp = call(request().url(server.uri().resolve("/samples/problem").toString()))) {
            assertThat(resp.code(), is(409));
            assertThat(resp.header("content-type"), is("application/problem+json"));
            assertThat(resp.header("X-Test"), is("yes"));
            assertThat(resp.body().string(), is("{\"title\":\"Existing\"}"));
        }
    }

    @Test
    public void webApplicationExceptionsWithoutEntityConvertToProblemDetails() throws Exception {
        @Path("samples")
        class Sample {
            @GET
            public String client() {
                throw new BadRequestException("Username is required");
            }

            @GET
            @Path("/not-found")
            public String notFound() {
                throw new NotFoundException("Could not find resource");
            }

            @GET
            @Path("/headerful")
            public String headerful() {
                throw new WebApplicationException(jakarta.ws.rs.core.Response.status(429)
                    .header("Retry-After", "30")
                    .build());
            }

            @GET
            @Path("/server-error")
            public String serverError() {
                throw new InternalServerErrorException("Password xyz; database /private/path/customer.db");
            }
        }

        this.server = ServerUtils.httpsServerForTest().addHandler(restHandler(new Sample())).start();

        try (Response resp = call(request().url(server.uri().resolve("/samples").toString()))) {
            JSONObject json = new JSONObject(resp.body().string());
            assertThat(resp.code(), is(400));
            assertThat(json.getString("title"), is("Username is required"));
        }

        try (Response resp = call(request().url(server.uri().resolve("/samples/not-found").toString()))) {
            JSONObject json = new JSONObject(resp.body().string());
            assertThat(resp.code(), is(404));
            assertThat(json.getString("title"), is("Could not find resource"));
        }

        try (Response resp = call(request().url(server.uri().resolve("/samples/headerful").toString()))) {
            JSONObject json = new JSONObject(resp.body().string());
            assertThat(resp.code(), is(429));
            assertThat(resp.header("Retry-After"), is("30"));
            assertThat(json.getString("title"), containsString("Too Many Requests"));
        }

        try (Response resp = call(request().url(server.uri().resolve("/samples/server-error").toString()))) {
            String body = resp.body().string();
            JSONObject json = new JSONObject(body);
            assertThat(resp.code(), is(500));
            assertThat(json.getString("title"), is("Internal Server Error"));
            assertThat(json.getString("detail"), is("An unexpected error occurred"));
            assertThat(body, not(containsString("Password xyz")));
            assertThat(body, not(containsString("/private/path/customer.db")));
            assertThat(body, not(containsString("InternalServerErrorException")));
        }
    }

    @Test
    public void generatedProblemsReplaceRepresentationHeadersAndPreserveOtherHeaders() throws Exception {
        @Path("samples")
        class Sample {
            @GET
            public String get() {
                throw new WebApplicationException(jakarta.ws.rs.core.Response.status(429)
                    .header("Content-Type", "text/plain")
                    .header("Content-Length", "1")
                    .header("Content-Encoding", "gzip")
                    .header("Content-Language", "fr")
                    .header("Content-Location", "/old-representation")
                    .header("Content-Range", "bytes 0-0/1")
                    .header("Content-Disposition", "attachment; filename=old.txt")
                    .header("ETag", "\"old\"")
                    .header("Last-Modified", "Wed, 21 Oct 2015 07:28:00 GMT")
                    .header("Content-MD5", "old-md5")
                    .header("Digest", "sha-256=old")
                    .header("Content-Digest", "sha-256=:old:")
                    .header("Repr-Digest", "sha-256=:old:")
                    .header("Trailer", "Digest")
                    .header("Vary", "Accept, Origin")
                    .header("Retry-After", "30")
                    .header("Location", "/try-later")
                    .header("Cache-Control", "private")
                    .header("Set-Cookie", "one=1; Path=/")
                    .header("Set-Cookie", "two=2; Path=/")
                    .header("X-Test", "one")
                    .header("X-Test", "two")
                    .build());
            }
        }

        this.server = ServerUtils.httpsServerForTest().addHandler(restHandler(new Sample())).start();

        try (Response resp = call(request().url(server.uri().resolve("/samples").toString()))) {
            JSONObject json = new JSONObject(resp.body().string());
            assertThat(resp.code(), is(429));
            assertThat(resp.header("Content-Type"), is("application/problem+json"));
            assertThat(resp.header("Content-Length"), not(is("1")));
            assertThat(resp.header("Content-Encoding"), is(nullValue()));
            assertThat(resp.header("Content-Language"), is(nullValue()));
            assertThat(resp.header("Content-Location"), is(nullValue()));
            assertThat(resp.header("Content-Range"), is(nullValue()));
            assertThat(resp.header("Content-Disposition"), is(nullValue()));
            assertThat(resp.header("ETag"), is(nullValue()));
            assertThat(resp.header("Last-Modified"), is(nullValue()));
            assertThat(resp.header("Content-MD5"), is(nullValue()));
            assertThat(resp.header("Digest"), is(nullValue()));
            assertThat(resp.header("Content-Digest"), is(nullValue()));
            assertThat(resp.header("Repr-Digest"), is(nullValue()));
            assertThat(resp.header("Trailer"), is(nullValue()));
            assertThat(Arrays.stream(resp.header("Vary").split(","))
                .map(String::trim)
                .anyMatch("Accept"::equalsIgnoreCase), is(false));
            assertThat(resp.header("Vary"), containsString("Origin"));
            assertThat(resp.header("Retry-After"), is("30"));
            assertThat(resp.header("Location"), is(server.uri().resolve("/try-later").toString()));
            assertThat(resp.header("Cache-Control"), is("private"));
            assertThat(resp.headers("Set-Cookie"), is(Arrays.asList("one=1; Path=/", "two=2; Path=/")));
            assertThat(resp.headers("X-Test"), is(Arrays.asList("one", "two")));
            assertThat(json.getInt("status"), is(429));
        }
    }

    @Test
    public void headRequestsWithProblemDetailsResponsesHaveNoBody() throws Exception {
        @Path("samples")
        class Sample {
            @GET
            public String get() {
                throw new IllegalStateException("boom");
            }
        }
        this.server = ServerUtils.httpsServerForTest().addHandler(restHandler(new Sample())).start();

        try (Response resp = call(request().url(server.uri().resolve("/samples").toString()).head())) {
            assertThat(resp.code(), is(500));
            assertThat(resp.header("content-type"), is("application/problem+json"));
            assertThat(resp.body().bytes(), is(new byte[0]));
        }
    }

    @Test
    public void logOptionsHaveSafeDefaultsAndCanBeEnabled() {
        ProblemDetailsExceptionMapperBuilder builder = problemDetailsExceptionMapper();

        assertThat(builder.log4xxProblemDetailsInstanceIds(), is(false));
        assertThat(builder.log5xxProblemDetailsInstanceIds(), is(true));

        assertThat(builder.withLog4xxProblemDetailsInstanceIds(true).log4xxProblemDetailsInstanceIds(), is(true));
    }

    @AfterEach
    public void stop() {
        scaffolding.MuAssert.stopAndCheck(server);
    }
}
