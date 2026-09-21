package io.muserver.rest;

import io.muserver.MuServer;
import jakarta.ws.rs.*;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.time.*;
import java.util.List;

import static io.muserver.openapi.SchemaObjectBuilder.schemaObject;
import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static org.junit.Assert.*;
import static scaffolding.ClientUtils.*;
import static scaffolding.ServerUtils.httpsServerForTest;

public class JavaValueSchemaIntegrationTest {
    private MuServer server;
    @After public void stop() { if (server != null) server.stop(); }

    @Path("/values") public static class Values {
        @POST @Path("{day}") @Consumes("application/x-www-form-urlencoded")
        public String values(@PathParam("day") LocalDate day, @QueryParam("duration") Duration duration,
                             @MatrixParam("month") YearMonth month, @HeaderParam("When") OffsetDateTime when,
                             @CookieParam("year") Year year, @FormParam("time") LocalTime time) {
            return day + "|" + duration + "|" + month + "|" + when + "|" + year + "|" + time;
        }
        @GET public String query(@QueryParam("days") @DefaultValue("2021-02-12") List<LocalDate> days,
                                 @QueryParam("month") @Description(value="Billing month", example="2022-03") YearMonth month) {
            return days.toString();
        }
    }

    @Test public void valuesUseTheExistingParsersAcrossAllLocations() throws Exception {
        server = httpsServerForTest().addHandler(restHandler(new Values()).withOpenApiJsonUrl("/openapi.json")).start();
        try (okhttp3.Response response = call(request(server.uri().resolve("/values/2021-02-12;month=2021-02?duration=PT1H30M"))
            .header("When", "2021-02-12T15:33:28.123+01:00").header("Cookie", "year=2021")
            .post(new okhttp3.FormBody.Builder().add("time", "15:33:28.123").build()))) {
            assertEquals(200, response.code());
            assertEquals("2021-02-12|PT1H30M|2021-02|2021-02-12T15:33:28.123+01:00|2021|15:33:28.123", response.body().string());
        }
        JSONObject api = document();
        JSONObject operation = api.getJSONObject("paths").getJSONObject("/values").getJSONObject("get");
        JSONObject days = operation.getJSONArray("parameters").getJSONObject(0).getJSONObject("schema");
        assertEquals("array", days.getString("type"));
        assertEquals("date", days.getJSONObject("items").getString("format"));
        assertEquals("2021-02-12", days.getJSONArray("default").getString(0));
        JSONObject month = operation.getJSONArray("parameters").getJSONObject(1).getJSONObject("schema");
        assertEquals("Billing month", month.getString("description"));
        assertEquals("2022-03", month.getJSONArray("examples").getString(0));
    }

    @Test public void registrationsAndExplicitDocumentationPrecedeParameterCustomization() throws Exception {
        server = httpsServerForTest().addHandler(restHandler(new Values())
            .addCustomSchema(YearMonth.class, schemaObject().withType("string").withDescription("Registered month").build())
            .addSchemaObjectCustomizer((builder, context) -> {
                if (context.target() == SchemaObjectCustomizerTarget.PARAMETER && context.parameterName().orElse("").equals("month")
                    && context.parameterLocation().orElse("").equals("query")) {
                    assertEquals("Billing month", builder.description());
                    return builder.withDescription("Customized month");
                }
                return builder;
            }).withOpenApiJsonUrl("/openapi.json")).start();
        JSONObject api = document();
        JSONObject query = api.getJSONObject("paths").getJSONObject("/values").getJSONObject("get")
            .getJSONArray("parameters").getJSONObject(1).getJSONObject("schema");
        assertFalse(query.has("$ref"));
        assertEquals("Customized month", query.getString("description"));
        assertEquals("Registered month", api.getJSONObject("components").getJSONObject("schemas").getJSONObject("YearMonth").getString("description"));
    }

    private JSONObject document() throws Exception {
        try (okhttp3.Response response = call(request(server.uri().resolve("/openapi.json")))) {
            assertEquals(200, response.code());
            return new JSONObject(response.body().string());
        }
    }
}
