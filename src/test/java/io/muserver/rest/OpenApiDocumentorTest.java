package io.muserver.rest;

import io.muserver.MuServer;
import io.muserver.UploadedFile;
import io.muserver.openapi.InfoObjectBuilder;
import io.muserver.openapi.LicenseObjectBuilder;
import io.muserver.openapi.OpenAPIObjectBuilder;
import org.example.petstore.resource.PetResource;
import org.example.petstore.resource.PetStoreResource;
import org.example.petstore.resource.UserResource;
import org.example.petstore.resource.VehicleResource;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import scaffolding.MuAssert;
import scaffolding.ServerUtils;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.util.List;

import static io.muserver.openapi.ExternalDocumentationObjectBuilder.externalDocumentationObject;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class OpenApiDocumentorTest {

    private MuServer server;

    private static MuServer serverWithPetStore() {
        return ServerUtils.httpsServerForTest()
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
    }


    @Test
    public void hasJsonEndpoint() throws IOException {
        server = serverWithPetStore();
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


            JSONObject post = paths.getJSONObject("/pet/{petId}")
                .getJSONObject("post");

            JSONArray parameters = post.getJSONArray("parameters");
            assertThat(parameters.length(), is(1));
            JSONObject pathParam = parameters.getJSONObject(0);
            assertThat(pathParam.getString("name"), is("petId"));
            assertThat(pathParam.getBoolean("required"), is(true));
            JSONObject pathParamSchema = pathParam.getJSONObject("schema");
            assertThat(pathParamSchema.has("default"), is(false));
            assertThat(pathParamSchema.getString("format"), is("int64"));
            assertThat(pathParamSchema.getString("type"), is("integer"));

            JSONObject updateByFormData = post
                .getJSONObject("requestBody")
                .getJSONObject("content")
                .getJSONObject("application/x-www-form-urlencoded")
                .getJSONObject("schema");
            assertThat(updateByFormData.has("deprecated"), is(false));
            assertThat(updateByFormData.has("default"), is(false));

            JSONObject updateByFormDataName = updateByFormData.getJSONObject("properties")
                .getJSONObject("name");

            assertThat(updateByFormDataName.getString("type"), is("string"));
            assertThat(updateByFormDataName.getString("description"), is("Updated name of the pet - More details about that"));

        }
    }

    @Test
    public void canGenerateHtml() throws IOException {
        server = serverWithPetStore();
        try (okhttp3.Response resp = call(request().url(server.uri().resolve("/api.html").toString()))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.header("Content-Type"), equalTo("text/html;charset=utf-8"));
            String responseBody = resp.body().string();
            File outputFile = new File("target/openapi.html");
            System.out.println("Creating " + outputFile.getCanonicalPath() + " which is the sample API documentation for your viewing pleasure.");
            try (FileWriter fw = new FileWriter(outputFile)) {
                fw.write(responseBody);
            }
        }

    }

    @Test
    public void fileUploadsAreCorrect() throws IOException {

        @Path("/uploads")
        class FileUploadResource {
            @POST
            @Path("{ id : [0-9]+ }")
            @Consumes(javax.ws.rs.core.MediaType.MULTIPART_FORM_DATA)
            public void create(@PathParam("id") String id,
                               @Description("The list of images")
                               @FormParam("images") List<UploadedFile> images,
                               @FormParam("oneThing") UploadedFile oneThing,
                               @FormParam("requiredThing") @Required UploadedFile requiredThing) {
            }
        }

        server = ServerUtils.httpsServerForTest()
            .addHandler(RestHandlerBuilder.restHandler(new FileUploadResource()).withOpenApiJsonUrl("/openapi.json"))
            .start();
        try (okhttp3.Response resp = call(request().url(server.uri().resolve("/openapi.json").toString()))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.header("Content-Type"), equalTo("application/json"));
            String responseBody = resp.body().string();
            JSONObject json = new JSONObject(responseBody);
            JSONObject postOp = json.getJSONObject("paths")
                .getJSONObject("/uploads/{id}")
                .getJSONObject("post");

            assertThat(postOp.getString("operationId"), is("POST_uploads__id_"));

            System.out.println("postOp = " + postOp.toString(2));

            JSONArray pathParams = postOp.getJSONArray("parameters");
            assertThat(pathParams.length(), is(1));
            JSONObject pathParam = pathParams.getJSONObject(0);
            assertThat(pathParam.get("in"), is("path"));
            assertThat(pathParam.get("name"), is("id"));
            assertThat(pathParam.get("required"), is(true));
            assertThat(pathParam.getJSONObject("schema").getString("pattern"), is("[0-9]+"));

            JSONObject params = postOp
                .getJSONObject("requestBody")
                .getJSONObject("content")
                .getJSONObject("multipart/form-data")
                .getJSONObject("schema")
                .getJSONObject("properties");

            JSONObject oneThing = params.getJSONObject("oneThing");
            assertThat(oneThing.getString("type"), is("string"));
            assertThat(oneThing.getString("format"), is("binary"));
            assertThat(oneThing.has("description"), is(false));

            JSONObject images = params.getJSONObject("images");
            assertThat(images.getString("type"), is("array"));
            assertThat(images.getString("description"), is("The list of images"));
            JSONObject items = images.getJSONObject("items");
            assertThat(items.getString("type"), is("string"));
            assertThat(items.getString("format"), is("binary"));
        }
    }

    @Test
    public void messageBodiesWork() throws IOException {

        @Path("/bodies")
        class BodyResource {

            @POST
            public void nothing() {
            }

            @POST
            @Consumes("text/plain")
            public void create(String body) {
            }

            @POST
            @Consumes("application/json")
            @Description(value = "Creates a new thing somehow", details = "The format depends on the content type")
            public void createWithJson(@Required @Description(value = "An json in a format", example = "{ \"thing\": \"hing\" }") String json) {
            }

            @POST
            @Consumes("image/*")
            public void uploadImage(@Description(value="Image", details = "Any kind of image") File image) {
            }

            @POST
            @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
            public void uploadForm(@Description("The first") @FormParam("one") String one,
                                   @Description(value="The second", details = "And this one is required")
                                   @Required @FormParam("two") String two,
                                   @FormParam("three") int three) {
            }
        }

        server = ServerUtils.httpsServerForTest()
            .addHandler(RestHandlerBuilder.restHandler(new BodyResource()).withOpenApiJsonUrl("/openapi.json"))
            .start();
        try (okhttp3.Response resp = call(request(server.uri().resolve("/openapi.json")))) {
            JSONObject json = new JSONObject(resp.body().string());
            JSONObject postOp = json.getJSONObject("paths")
                .getJSONObject("/bodies")
                .getJSONObject("post");

            assertThat(postOp.getString("operationId"), is("POST_bodies"));

            // When there are multiple paths with the same method, then any one that has a description annotation is used
            assertThat(postOp.getString("summary"), is("Creates a new thing somehow"));
            assertThat(postOp.getString("description"), is("The format depends on the content type"));

            JSONObject requestBody = postOp.getJSONObject("requestBody");
            assertThat(requestBody.has("description"), is(false)); // descriptions are added on the schemas
            assertThat(requestBody.getBoolean("required"), is(false));


            JSONObject content = requestBody.getJSONObject("content");
            assertThat(content.keySet(), hasSize(4));

            JSONObject image = content.getJSONObject("image/*").getJSONObject("schema");
            assertThat(image.keySet(), hasSize(3));
            assertThat(image.getBoolean("nullable"), is(true));
            assertThat(image.getString("title"), is("Image"));
            assertThat(image.getString("description"), is("Any kind of image"));

            JSONObject appJson = content.getJSONObject("application/json").getJSONObject("schema");
            assertThat(appJson.keySet(), hasSize(2));
            assertThat(appJson.getBoolean("nullable"), is(false));
            assertThat(appJson.getString("title"), is("An json in a format"));

            JSONObject textPlain = content.getJSONObject("text/plain").getJSONObject("schema");
            assertThat(textPlain.keySet(), hasSize(1));
            assertThat(textPlain.getBoolean("nullable"), is(true));

            assertThat(content.has("application/x-www-form-urlencoded"), is(true));
        }
    }

    @After
    public void cleanup() {
        MuAssert.stopAndCheck(server);
    }

}
