package io.muserver.rest;

import io.muserver.MuServer;
import io.muserver.openapi.SchemaObjectBuilder;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import scaffolding.MuAssert;

import javax.ws.rs.*;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;
import static scaffolding.ServerUtils.httpsServerForTest;

public class OpenApiSchemaCustomizerTest {

    private MuServer server;

    @Test
    public void requestBodiesCanBeCustomized() throws Exception {
        @Path("/blah")
        class Blah implements SchemaObjectCustomizer {

            @GET
            @Consumes("application/json")
            public void getJson(@Description(value = "json-title", details = "json-description") String body) {
            }

            @GET
            @Consumes("text/plain")
            public void getText(@Description(value = "text-title", details = "text-description") String body) {
            }

            @Override
            public SchemaObjectBuilder customize(SchemaObjectBuilder builder, SchemaObjectCustomizerContext context) {
                if (context.target() == SchemaObjectCustomizerTarget.REQUEST_BODY && context.resource() == this) {
                    builder.withTitle(builder.title().toUpperCase());
                    builder.withDescription(builder.description().toUpperCase());
                    if (context.methodHandle().get().getName().equals("getText")) {
                        builder.withType("object")
                            .withProperties(Collections.singletonMap("a-thing", SchemaObjectBuilder.schemaObjectFrom(Integer.class).build()));
                    }
                }
                return builder;
            }
        }

        server = httpsServerForTest()
            .addHandler(RestHandlerBuilder.restHandler(new Blah()).withOpenApiJsonUrl("/openapi.json"))
            .start();
        try (okhttp3.Response resp = call(request(server.uri().resolve("/openapi.json")))) {
            JSONObject json = new JSONObject(resp.body().string());
            JSONObject requestBody = (JSONObject) json.query("/paths/~1blah/get/requestBody");
            assertThat(requestBody.query("/content/application~1json/schema/title"), equalTo("JSON-TITLE"));
            assertThat(requestBody.query("/content/application~1json/schema/description"), equalTo("JSON-DESCRIPTION"));
            assertThat(requestBody.query("/content/application~1json/schema/type"), equalTo("string"));
            assertThat(requestBody.query("/content/text~1plain/schema/title"), equalTo("TEXT-TITLE"));
            assertThat(requestBody.query("/content/text~1plain/schema/description"), equalTo("TEXT-DESCRIPTION"));
            assertThat(requestBody.query("/content/text~1plain/schema/type"), equalTo("object"));
            assertThat(requestBody.query("/content/text~1plain/schema/properties/a-thing/format"), equalTo("int32"));
        }
    }

    @SuppressWarnings("unused")
    private enum SomeEnum {
        ONE, TWO, THREE
    }

    @Test
    public void responseBodiesCanBeCustomized() throws Exception {
        @Path("/blah")
        class Blah implements SchemaObjectCustomizer {

            @GET
            @Produces("application/json")
            @Description("Hmm")
            @ApiResponse(code = "200", message = "JSON Success", example = "{\"example\":true}")
            @ApiResponse(code = "400", message = "JSON Error", response = List.class)
            public String getJson() {
                return "";
            }

            @GET
            @Produces("text/plain")
            @Description("get-plain")
            @ApiResponse(code = "200", message = "Plain Success", example = "success-example")
            @ApiResponse(code = "500", message = "Server error", response = String.class)
            public SomeEnum getText() {
                return SomeEnum.ONE;
            }

            @Override
            public SchemaObjectBuilder customize(SchemaObjectBuilder builder, SchemaObjectCustomizerContext context) {
                if (context.target() == SchemaObjectCustomizerTarget.RESPONSE_BODY && context.resource() == this) {
                    if (context.mediaType().getSubtype().equals("json")) {
                        builder.withTitle("json schema title");
                    } else {
                        builder.withTitle("text schema title");
                        if (SomeEnum.class.isAssignableFrom(context.type())) {
                            builder.withNullable(false);
                        }
                    }
                } else {
                    throw new RuntimeException("Unexpected! " + context);
                }
                return builder;
            }
        }

        server = httpsServerForTest()
            .addHandler(RestHandlerBuilder.restHandler(new Blah()).withOpenApiJsonUrl("/openapi.json"))
            .start();
        try (okhttp3.Response resp = call(request(server.uri().resolve("/openapi.json")))) {
            JSONObject json = new JSONObject(resp.body().string());
            JSONObject resp200 = (JSONObject) json.query("/paths/~1blah/get/responses/200");
            assertThat(resp200.query("/content/application~1json/schema/title"), equalTo("json schema title"));
            assertThat(resp200.query("/content/text~1plain/schema/title"), equalTo("text schema title"));
            assertThat(((JSONArray)resp200.query("/content/text~1plain/schema/enum")).toList(), contains("ONE", "TWO", "THREE"));
            JSONObject resp400 = (JSONObject) json.query("/paths/~1blah/get/responses/400");
            assertThat(resp400.query("/content/application~1json/schema/title"), equalTo("json schema title"));
        }
    }

    @Test
    public void examplesCanBeGenerated() throws Exception {
        @Path("/blah")
        class Blah implements SchemaObjectCustomizer {

            @GET
            @Produces("application/json")
            public String getJson() {
                return "";
            }

            @Override
            public SchemaObjectBuilder customize(SchemaObjectBuilder builder, SchemaObjectCustomizerContext context) {
                if (context.target() == SchemaObjectCustomizerTarget.RESPONSE_BODY && context.resource() == this) {
                    builder.withExample("This is my example");
                } else {
                    throw new RuntimeException("Unexpected! " + context);
                }
                return builder;
            }
        }

        server = httpsServerForTest()
            .addHandler(RestHandlerBuilder.restHandler(new Blah()).withOpenApiJsonUrl("/openapi.json"))
            .start();
        try (okhttp3.Response resp = call(request(server.uri().resolve("/openapi.json")))) {
            JSONObject json = new JSONObject(resp.body().string());
            JSONObject resp200 = (JSONObject) json.query("/paths/~1blah/get/responses/200/content/application~1json");
            assertThat(resp200.getString("example"), equalTo("This is my example"));
        }
    }

    @Test
    public void formParametersCanBeCustomized() throws Exception {
        @Path("/blah")
        class Blah implements SchemaObjectCustomizer {

            @GET
            @Consumes("application/x-www-form-urlencoded")
            public void getJson(@FormParam("itIsSomeEnum") SomeEnum someEnum, @FormParam("Umm") List<String> umm) {
            }

            @Override
            public SchemaObjectBuilder customize(SchemaObjectBuilder builder, SchemaObjectCustomizerContext context) {
                if (context.target() == SchemaObjectCustomizerTarget.FORM_PARAM && context.resource() == this) {
                    String paramName = context.parameterName().get();
                    if (paramName.equals("Umm")) {
                        builder.withExample("Ummmmmmmmmmmmmmmmm");
                    } else if (paramName.equals("itIsSomeEnum")) {
                        builder.withNullable(false);
                    } else {
                        throw new RuntimeException("Unexpected!! " + context);
                    }
                } else {
                    throw new RuntimeException("Unexpected! " + context);
                }
                return builder;
            }
        }

        server = httpsServerForTest()
            .addHandler(RestHandlerBuilder.restHandler(new Blah()).withOpenApiJsonUrl("/openapi.json"))
            .start();
        try (okhttp3.Response resp = call(request(server.uri().resolve("/openapi.json")))) {
            JSONObject json = new JSONObject(resp.body().string());
            JSONObject props = (JSONObject) json.query("/paths/~1blah/get/requestBody/content/application~1x-www-form-urlencoded/schema/properties");
            assertThat(props.query("/Umm/example"), equalTo("Ummmmmmmmmmmmmmmmm"));
            assertThat(props.query("/itIsSomeEnum/nullable"), equalTo(false));
        }
    }

    @AfterEach
    public void cleanup() {
        MuAssert.stopAndCheck(server);
    }

}
