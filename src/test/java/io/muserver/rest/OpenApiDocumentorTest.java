package io.muserver.rest;

import io.muserver.MuServer;
import io.muserver.openapi.InfoObjectBuilder;
import io.muserver.openapi.LicenseObjectBuilder;
import io.muserver.openapi.OpenAPIObjectBuilder;
import org.example.petstore.resource.PetResource;
import org.example.petstore.resource.PetStoreResource;
import org.example.petstore.resource.UserResource;
import org.example.petstore.resource.VehicleResource;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;

import static io.muserver.MuServerBuilder.httpServer;
import static io.muserver.openapi.ExternalDocumentationObjectBuilder.externalDocumentationObject;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class OpenApiDocumentorTest {

    private final MuServer server = httpServer()
        .addHandler(RestHandlerBuilder.restHandler(
            new PetResource(), new PetStoreResource(), new UserResource(), new VehicleResource()
            ).withOpenApiDocument(OpenAPIObjectBuilder.openAPIObject()
                .withInfo(InfoObjectBuilder.infoObject()
                    .withTitle("Mu Server Sample API")
                    .withVersion("1.0")
                    .withLicense(LicenseObjectBuilder.Apache2_0())
                    .withDescription("This is the **description**\n\nWhich is markdown")
                    .withTermsOfService(URI.create("http://swagger.io/terms/"))
                    .build())
                .withExternalDocs(externalDocumentationObject()
                    .withDescription("The swagger version of this API")
                    .withUrl(URI.create("http://petstore.swagger.io"))
                    .build()))
            .withOpenApiJsonUrl("/openapi.json")
            .withOpenApiHtmlUrl("/api.html")
        )
        .start();


    @Test
    public void hasJsonEndpoint() throws IOException {
        try (okhttp3.Response resp = call(request().url(server.uri().resolve("/openapi.json").toString()))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.header("Content-Type"), equalTo("application/json"));
            String responseBody = resp.body().string();
            JSONObject json = new JSONObject(responseBody);
            JSONObject paths = json.getJSONObject("paths");

            JSONObject pet = paths.getJSONObject("/pet");
            assertThat(pet.keySet(), containsInAnyOrder("put", "post"));

            JSONObject download = paths.getJSONObject("/pet/{petId}/download").getJSONObject("get");
            assertThat(download.getBoolean("deprecated"), is(false));
            JSONArray downloadParams = download.getJSONArray("parameters");
            assertThat(downloadParams.length(), is(1));
            JSONObject downloadParam1 = downloadParams.getJSONObject(0);
            assertThat(downloadParam1.getString("name"), equalTo("petId"));
            assertThat(downloadParam1.getString("description"), equalTo("ID of pet that needs to be fetched"));


            JSONObject findByTags = paths.getJSONObject("/pet/findByTags").getJSONObject("get");
            assertThat(findByTags.getBoolean("deprecated"), is(true));
            JSONObject findByTagsParam = findByTags.getJSONArray("parameters").getJSONObject(1);
            assertThat(findByTagsParam.getString("name"), equalTo("tags"));
            assertThat(findByTagsParam.getString("in"), equalTo("query"));
            assertThat(findByTagsParam.getJSONObject("schema").getString("type"), equalTo("string"));


            JSONObject updateByFormData = paths.getJSONObject("/pet/{petId}")
                .getJSONObject("post")
                .getJSONObject("requestBody")
                .getJSONObject("content")
                .getJSONObject("application/x-www-form-urlencoded")
                .getJSONObject("schema");
            assertThat(updateByFormData.has("deprecated"), is(false));

            JSONObject updateByFormDataName = updateByFormData.getJSONObject("properties")
                .getJSONObject("name");

            assertThat(updateByFormDataName.getString("type"), is("string"));
            assertThat(updateByFormDataName.getString("description"), is("Updated name of the pet"));

        }
    }

    @Test
    public void canGenerateHtml() throws IOException {
        try (okhttp3.Response resp = call(request().url(server.uri().resolve("/api.html").toString()))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.header("Content-Type"), equalTo("text/html"));
            String responseBody = resp.body().string();
            File outputFile = new File("target/openapi.html");
            System.out.println("Creating " + outputFile.getCanonicalPath() + " which is the sample API documentation for your viewing pleasure.");
            try (FileWriter fw = new FileWriter(outputFile)) {
                fw.write(responseBody);
            }
        }

    }

}
