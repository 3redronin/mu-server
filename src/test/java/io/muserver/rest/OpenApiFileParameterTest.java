package io.muserver.rest;

import io.muserver.MuServer;
import jakarta.ws.rs.*;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import static io.muserver.openapi.SchemaObjectBuilder.schemaObject;
import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static org.junit.jupiter.api.Assertions.*;
import static scaffolding.ClientUtils.*;
import static scaffolding.ServerUtils.httpsServerForTest;

public class OpenApiFileParameterTest {
    private MuServer server;
    @AfterEach public void stop() { if (server != null) server.stop(); }

    @Path("/files") public static class FilesResource {
        @POST @Consumes("multipart/form-data") @Produces("text/plain")
        public String files(@FormParam("upload") File upload, @FormParam("paths") List<File> paths,
                            @FormParam("array") File[] array) throws Exception {
            return Files.readString(upload.toPath()) + "|" + paths.get(0).getPath() + "|" + paths.get(1).getPath()
                + "|" + array[0].getPath();
        }
    }

    @Test public void scalarFilesAreUploadsWhileCollectionsContainConvertedPaths() throws Exception {
        server = httpsServerForTest().addHandler(restHandler(new FilesResource()).withOpenApiJsonUrl("/openapi.json")).start();
        okhttp3.RequestBody body = new okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("upload", "example.txt", okhttp3.RequestBody.create("uploaded content", okhttp3.MediaType.get("application/octet-stream")))
            .addFormDataPart("paths", "reports/one.txt").addFormDataPart("paths", "reports/two.txt")
            .addFormDataPart("array", "reports/three.txt").build();
        try (okhttp3.Response response = call(request(server.uri().resolve("/files")).post(body))) {
            assertEquals(200, response.code());
            assertEquals("uploaded content|reports/one.txt|reports/two.txt|reports/three.txt", response.body().string());
        }
        JSONObject media = media(document());
        JSONObject properties = media.getJSONObject("schema").getJSONObject("properties");
        assertEquals("{}", properties.getJSONObject("upload").toString());
        assertEquals("application/octet-stream", media.getJSONObject("encoding").getJSONObject("upload").getString("contentType"));
        for (String name : new String[]{"paths", "array"}) {
            JSONObject schema = properties.getJSONObject(name);
            assertEquals("array", schema.getString("type"));
            JSONObject item = schema.getJSONObject("items");
            assertEquals("string", item.getString("type"));
            assertTrue(item.getString("description").contains("filesystem path"));
            assertFalse(item.has("format"));
            assertFalse(media.getJSONObject("encoding").has(name));
        }
    }

    @Test public void explicitFileSchemasAndCustomizersRemainAuthoritative() throws Exception {
        server = httpsServerForTest().addHandler(restHandler(new FilesResource())
            .addCustomSchema(File.class, schemaObject().withType("string").withFormat("binary").build())
            .addSchemaObjectCustomizer((builder, context) -> {
                if (context.target() == SchemaObjectCustomizerTarget.FORM_PARAM
                    && context.parameterName().orElse("").equals("upload")) {
                    assertEquals("binary", builder.format());
                    return builder.withDescription("Explicit upload contract");
                }
                return builder;
            }).withOpenApiJsonUrl("/openapi.json")).start();
        JSONObject api = document();
        JSONObject properties = media(api).getJSONObject("schema").getJSONObject("properties");
        JSONObject upload = properties.getJSONObject("upload");
        assertEquals("binary", upload.getString("format"));
        assertEquals("Explicit upload contract", upload.getString("description"));
        assertEquals("#/components/schemas/File", properties.getJSONObject("paths").getJSONObject("items").getString("$ref"));
        assertEquals("binary", api.getJSONObject("components").getJSONObject("schemas").getJSONObject("File").getString("format"));
    }

    private JSONObject document() throws Exception {
        try (okhttp3.Response response = call(request(server.uri().resolve("/openapi.json")))) {
            assertEquals(200, response.code());
            return new JSONObject(response.body().string());
        }
    }

    private JSONObject media(JSONObject api) {
        return api.getJSONObject("paths").getJSONObject("/files").getJSONObject("post")
            .getJSONObject("requestBody").getJSONObject("content").getJSONObject("multipart/form-data");
    }
}
