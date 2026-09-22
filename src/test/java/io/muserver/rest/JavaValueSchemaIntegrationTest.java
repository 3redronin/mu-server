package io.muserver.rest;

import io.muserver.MuServer;
import jakarta.ws.rs.*;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.time.*;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

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

    @Path("/formats") @Consumes("text/plain") @Produces("text/plain")
    public static class Formats {
        @POST @Path("time") public LocalTime time(LocalTime value) { return value; }
        @POST @Path("date-time") public LocalDateTime dateTime(LocalDateTime value) { return value; }
        @POST @Path("offset-time") public OffsetTime offsetTime(OffsetTime value) { return value; }
        @POST @Path("offset-date-time") public OffsetDateTime offsetDateTime(OffsetDateTime value) { return value; }
        @GET public String query(@QueryParam("times") List<LocalTime> times,
                                 @QueryParam("time") LocalTime time, @QueryParam("date-time") LocalDateTime dateTime,
                                 @QueryParam("offset-time") OffsetTime offsetTime, @QueryParam("offset-date-time") OffsetDateTime offsetDateTime) {
            return time + "|" + dateTime + "|" + offsetTime + "|" + offsetDateTime;
        }
        @GET @Path("file") public String file(@QueryParam("file") java.io.File file) { return file.toString(); }
        @POST @Path("form") @Consumes("application/x-www-form-urlencoded")
        public String form(@FormParam("time") LocalTime time, @FormParam("date-time") LocalDateTime dateTime,
                           @FormParam("offset-time") OffsetTime offsetTime, @FormParam("offset-date-time") OffsetDateTime offsetDateTime) {
            return time + "|" + dateTime + "|" + offsetTime + "|" + offsetDateTime;
        }
    }

    @Test public void inputFormatsDescribePreferredSyntaxWithoutChangingParsingOrResponseFormats() throws Exception {
        server = httpsServerForTest().addHandler(restHandler(new Formats()).withOpenApiJsonUrl("/openapi.json")).start();
        Map<String, String[]> cases = new LinkedHashMap<>();
        cases.put("time", new String[]{"time-local", "15:33"});
        cases.put("date-time", new String[]{"date-time-local", "2021-02-12T15:33"});
        cases.put("offset-time", new String[]{"time", "15:33+01:00"});
        cases.put("offset-date-time", new String[]{"date-time", "2021-02-12T15:33+01:00"});
        JSONObject paths = document().getJSONObject("paths");
        JSONObject formProperties = paths.getJSONObject("/formats/form").getJSONObject("post")
            .getJSONObject("requestBody").getJSONObject("content").getJSONObject("application/x-www-form-urlencoded")
            .getJSONObject("schema").getJSONObject("properties");
        org.json.JSONArray queryParameters = paths.getJSONObject("/formats").getJSONObject("get").getJSONArray("parameters");
        okhttp3.FormBody.Builder formBody = new okhttp3.FormBody.Builder();
        okhttp3.HttpUrl.Builder queryUrl = okhttp3.HttpUrl.get(server.uri().resolve("/formats")).newBuilder();
        for (Map.Entry<String, String[]> entry : cases.entrySet()) {
            String name = entry.getKey();
            assertEquals(entry.getValue()[0], formProperties.getJSONObject(name).getString("format"));
            boolean found = false;
            for (int i = 0; i < queryParameters.length(); i++) {
                JSONObject parameter = queryParameters.getJSONObject(i);
                if (name.equals(parameter.getString("name"))) {
                    assertEquals(entry.getValue()[0], parameter.getJSONObject("schema").getString("format"));
                    found = true;
                }
            }
            assertTrue("Query parameter " + name, found);
            formBody.add(name, entry.getValue()[1]);
            queryUrl.addQueryParameter(name, entry.getValue()[1]);
            JSONObject operation = paths.getJSONObject("/formats/" + entry.getKey()).getJSONObject("post");
            JSONObject input = operation.getJSONObject("requestBody").getJSONObject("content")
                .getJSONObject("text/plain").getJSONObject("schema");
            assertEquals(entry.getValue()[0], input.getString("format"));
            assertTrue(input.getString("description").contains("seconds"));
            JSONObject output = operation.getJSONObject("responses").getJSONObject("200").getJSONObject("content")
                .getJSONObject("text/plain").getJSONObject("schema");
            assertFalse(output.has("format"));
            try (okhttp3.Response response = call(request(server.uri().resolve("/formats/" + entry.getKey()))
                .post(okhttp3.RequestBody.create(entry.getValue()[1], okhttp3.MediaType.get("text/plain"))))) {
                assertEquals(200, response.code());
                assertEquals(entry.getValue()[1], response.body().string());
            }
        }
        String expected = "15:33|2021-02-12T15:33|15:33+01:00|2021-02-12T15:33+01:00";
        try (okhttp3.Response response = call(request().url(queryUrl.build()))) {
            assertEquals(200, response.code());
            assertEquals(expected, response.body().string());
        }
        try (okhttp3.Response response = call(request(server.uri().resolve("/formats/form")).post(formBody.build()))) {
            assertEquals(200, response.code());
            assertEquals(expected, response.body().string());
        }
        org.json.JSONArray parameters = paths.getJSONObject("/formats").getJSONObject("get").getJSONArray("parameters");
        assertEquals("time-local", parameters.getJSONObject(0).getJSONObject("schema").getJSONObject("items").getString("format"));
        JSONObject file = paths.getJSONObject("/formats/file").getJSONObject("get").getJSONArray("parameters")
            .getJSONObject(0).getJSONObject("schema");
        assertEquals("{}", file.toString());
        assertEquals("{}", io.muserver.openapi.SchemaObjectBuilder.schemaObjectFrom(java.io.File.class).build().toString());
    }

    @Test public void scalarFormatsMatchTheirJavaValues() {
        Class<?>[] types = {byte.class, Byte.class, short.class, Short.class, char.class, Character.class, java.math.BigDecimal.class};
        String[] formats = {"int8", "int8", "int16", "int16", "char", "char", "decimal"};
        for (int i = 0; i < types.length; i++) {
            assertEquals(formats[i], io.muserver.openapi.SchemaObjectBuilder.schemaObjectFrom(types[i]).format());
            jakarta.ws.rs.ext.ParamConverter<?> converter = new BuiltInParamConverterProvider()
                .getConverter(types[i], types[i], new java.lang.annotation.Annotation[0]);
            assertNotNull(converter);
            assertEquals(formats[i], JavaValueSchemas.parameter(types[i], converter).format());
        }
    }

    @Test public void temporalFormatInferenceStillHonorsRegistrationsAndCustomizers() throws Exception {
        server = httpsServerForTest().addHandler(restHandler(new Formats())
            .addCustomSchema(LocalTime.class, schemaObject().withType("string").withFormat("application-time").build())
            .addSchemaObjectCustomizer((builder, context) -> {
                if (context.type() == LocalDateTime.class) return builder.withFormat("application-date-time");
                return builder;
            }).withOpenApiJsonUrl("/openapi.json")).start();
        JSONObject api = document();
        assertEquals("application-time", api.getJSONObject("components").getJSONObject("schemas")
            .getJSONObject("LocalTime").getString("format"));
        JSONObject paths = api.getJSONObject("paths");
        for (String endpoint : new String[]{"time", "date-time"}) {
            JSONObject operation = paths.getJSONObject("/formats/" + endpoint).getJSONObject("post");
            JSONObject input = operation.getJSONObject("requestBody").getJSONObject("content").getJSONObject("text/plain").getJSONObject("schema");
            JSONObject output = operation.getJSONObject("responses").getJSONObject("200").getJSONObject("content").getJSONObject("text/plain").getJSONObject("schema");
            for (JSONObject schema : new JSONObject[]{input, output}) {
                if (endpoint.equals("time")) assertEquals("#/components/schemas/LocalTime", schema.getString("$ref"));
                else assertEquals("application-date-time", schema.getString("format"));
            }
        }
    }

    private JSONObject document() throws Exception {
        try (okhttp3.Response response = call(request(server.uri().resolve("/openapi.json")))) {
            assertEquals(200, response.code());
            return new JSONObject(response.body().string());
        }
    }
}
