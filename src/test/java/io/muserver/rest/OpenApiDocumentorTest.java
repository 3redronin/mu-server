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
import javax.ws.rs.core.Response;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static io.muserver.openapi.ExternalDocumentationObjectBuilder.externalDocumentationObject;
import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class OpenApiDocumentorTest {

    private MuServer server;

    private static MuServer serverWithPetStore() {
        return ServerUtils.httpsServerForTest()
            .addHandler(restHandler(
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
            assertThat(download.has("deprecated"), is(false));
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
            .addHandler(restHandler(new FileUploadResource()).withOpenApiJsonUrl("/openapi.json"))
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
    public void messageBodiesWork() throws Exception {

        @Path("/bodies")
        class BodyResource {

            @POST
            public void nothing() {
            }

            @POST
            @Consumes("text/plain")
            @Produces("text/plain;charset=utf-8")
            @ApiResponse(code = "201", message = "Very Success", contentType = {"text/plain;charset=utf-8"}, example = "This is an example return value")
            public int create(int body) {
                return body;
            }

            @POST
            @Consumes("application/json")
            @Description(value = "Creates a new thing somehow", details = "The format depends on the content type")
            @ApiResponse(code = "204", message = "Success")
            public void createWithJson(@Required @Description(value = "An json in a format", example = "{ \"thing\": \"hing\" }") String json) {
            }

            @POST
            @Consumes("image/*")
            @Produces("image/*")
            public File uploadImage(@Description(value = "Image", details = "Any kind of image") File image) {
                return image;
            }

            @POST
            @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
            @ApiResponse(code = "201", message = "Very Success", contentType = "application/json")
            public void uploadForm(@Description("The first") @FormParam("one") String one,
                                   @Description(value = "The second", details = "And this one is required")
                                   @Required @FormParam("two") String two,
                                   @FormParam("three") int three) {
            }
        }

        server = ServerUtils.httpsServerForTest()
            .addHandler(restHandler(new BodyResource()).withOpenApiJsonUrl("/openapi.json"))
            .start();

        try (okhttp3.Response resp = call(request(server.uri().resolve("/openapi.json")))) {
            JSONObject json = new JSONObject(resp.body().string());
//            System.out.println("json.toString(2) = " + json.toString(2));

            JSONObject postOp = json.getJSONObject("paths")
                .getJSONObject("/bodies")
                .getJSONObject("post");

            assertThat(postOp.getString("operationId"), is("POST_bodies"));

            // When there are multiple paths with the same method, then any one that has a description annotation is used
            assertThat(postOp.getString("summary"), is("Creates a new thing somehow"));
            assertThat(postOp.getString("description"), is("The format depends on the content type"));

            JSONObject requestBody = postOp.getJSONObject("requestBody");
            assertThat(requestBody.has("description"), is(true));
            assertThat(requestBody.getBoolean("required"), is(false));


            JSONObject content = requestBody.getJSONObject("content");
            assertThat(content.keySet(), hasSize(4));

            JSONObject image = content.getJSONObject("image/*").getJSONObject("schema");
            assertThat(image.keySet(), hasSize(5));
            assertThat(image.getBoolean("nullable"), is(true));
            assertThat(image.getString("title"), is("Image"));
            assertThat(image.getString("description"), is("Any kind of image"));
            assertThat(image.getString("type"), is("string"));
            assertThat(image.getString("format"), is("binary"));

            JSONObject appJson = content.getJSONObject("application/json").getJSONObject("schema");
            assertThat(appJson.keySet(), hasSize(3));
            assertThat(appJson.getBoolean("nullable"), is(true));
            assertThat(appJson.getString("title"), is("An json in a format"));
            assertThat(appJson.getString("type"), is("string"));

            JSONObject textPlain = content.getJSONObject("text/plain").getJSONObject("schema");
            assertThat(textPlain.keySet(), hasSize(3));
            assertThat(textPlain.getBoolean("nullable"), is(false));
            assertThat(textPlain.getString("type"), is("integer"));
            assertThat(textPlain.getString("format"), is("int32"));

            assertThat(content.has("application/x-www-form-urlencoded"), is(true));

            JSONObject responses = postOp.getJSONObject("responses");
            assertThat(responses.keySet(), hasSize(3));

            JSONObject twoXX = responses.getJSONObject("201").getJSONObject("content").getJSONObject("text/plain;charset=utf-8");
            assertThat(twoXX.get("example"), is("This is an example return value"));

            // although the schema can be guessed occasionally, adding it bloats the UI in swagger-ui so better not to have it
            assertThat(twoXX.has("schema"), is(false));

            JSONObject twoHundred = responses.getJSONObject("200").getJSONObject("content");
            assertThat(twoHundred
                .getJSONObject(twoHundred.keySet().stream().findFirst().get()).has("example"), is(false));
        }
    }

    @Test
    public void responseContentTypesAreDoneRight() throws IOException {
        @Path("sample")
        class Blah {
            @GET
            @ApiResponse(code = "204", message = "Should have no content field")
            @ApiResponse(code = "200", message = "Should have content field")
            @ApiResponse(code = "202", message = "Should have content field", response = int.class,
                responseHeaders = @ResponseHeader(name = "x-return-header", description = "A header to be returned"))
            @ApiResponse(code = "201", message = "Should have content field and type", contentType = "text/plain")
            public Response doIt() {
                return Response.ok().build();
            }

            @GET
            @Path("with-produces")
            @Produces("application/json")
            @ApiResponse(code = "204", message = "Should have no content field")
            @ApiResponse(code = "200", message = "Should have content field")
            @ApiResponse(code = "202", message = "Should have content field", response = int.class,
                responseHeaders = @ResponseHeader(name = "x-return-header", description = "A header to be returned"))
            @ApiResponse(code = "201", message = "Should have content field and type", contentType = "text/plain")
            public Response doIt2() {
                return Response.ok().build();
            }
        }
        server = ServerUtils.httpsServerForTest()
            .addHandler(RestHandlerBuilder.restHandler(new Blah()).withOpenApiJsonUrl("/openapi.json"))
            .start();
        try (okhttp3.Response resp = call(request(server.uri().resolve("/openapi.json")))) {
            JSONObject json = new JSONObject(resp.body().string());

            String[] paths = {"/sample", "/sample/with-produces"};
            for (String path : paths) {
                JSONObject op = json.getJSONObject("paths")
                    .getJSONObject(path)
                    .getJSONObject("get")
                    .getJSONObject("responses");
                String defaultType = path.equals("/sample") ? "*/*" : "application/json";

                assertThat(path, op.keySet(), hasSize(4));

                JSONObject _200 = op.getJSONObject("200");
                assertThat(path, _200.keySet(), hasSize(2));
                assertThat(path, _200.getString("description"), is("Should have content field"));
                assertThat(path, _200.getJSONObject("content").getJSONObject(defaultType).keySet(), hasSize(0));

                JSONObject _201 = op.getJSONObject("201");
                assertThat(path, _201.keySet(), hasSize(2));
                assertThat(path, _201.getString("description"), is("Should have content field and type"));
                assertThat(path, _201.getJSONObject("content").getJSONObject("text/plain").keySet(), hasSize(0));

                JSONObject _202 = op.getJSONObject("202");
                assertThat(path, _202.keySet(), hasSize(3));
                assertThat(path, _202.getString("description"), is("Should have content field"));
                assertThat(path, _202.getJSONObject("headers").getJSONObject("x-return-header").getString("description"),
                    is("A header to be returned"));
                assertThat(path, _202.getJSONObject("content").getJSONObject(defaultType).getJSONObject("schema").getString("type"), is("integer"));

                JSONObject _204 = op.getJSONObject("204");
                assertThat(path, _204.keySet(), hasSize(1));
                assertThat(path, _204.getString("description"), is("Should have no content field"));
            }
        }

    }

    @Test
    public void queryAndFormParamsWork() throws IOException {
        @Path("sample")
        class Blah {
            @POST
            @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
            public void blah(
                @QueryParam("id") int id,
                @QueryParam("id2") @Required int id2,
                @FormParam("id3") int id3,
                @FormParam("id4") @Required int id4
            ) {
            }
        }
        server = ServerUtils.httpsServerForTest()
            .addHandler(RestHandlerBuilder.restHandler(new Blah()).withOpenApiJsonUrl("/openapi.json"))
            .start();
        try (okhttp3.Response resp = call(request(server.uri().resolve("/openapi.json")))) {
            JSONObject json = new JSONObject(resp.body().string());

            String[] paths = {"/sample"};
            for (String path : paths) {
                JSONObject op = json.getJSONObject("paths")
                    .getJSONObject(path)
                    .getJSONObject("post");

                JSONObject bodySchema = op.getJSONObject("requestBody").getJSONObject("content").getJSONObject("application/x-www-form-urlencoded")
                    .getJSONObject("schema");
                assertThat(bodySchema.getString("type"), is("object"));
                assertThat(bodySchema.getJSONArray("required").toString(), equalTo(new JSONArray().put("id4").toString()));
                JSONObject id3 = bodySchema.getJSONObject("properties").getJSONObject("id3");
                assertThat(id3.keySet(), hasSize(4));
                assertThat(id3.getString("type"), is("integer"));
                assertThat(id3.getString("format"), is("int32"));
                assertThat(id3.getBoolean("nullable"), is(false));
                assertThat(id3.getInt("default"), is(0));
            }
        }
    }

    @Test
    public void classesAppearInTheOrderRegistered() throws IOException {

        @Path("/a")
        class Apple {
            @GET
            public void blah() {
            }
        }
        @Path("/b")
        class Carrot {
            @GET
            public void blah() {
            }
        }
        @Path("/c")
        class Banana {
            @GET
            public void blah() {
            }
        }

        server = ServerUtils.httpsServerForTest()
            .addHandler(RestHandlerBuilder.restHandler(
                new Apple(), new Banana(), new Carrot()
            ).withOpenApiJsonUrl("/openapi.json"))
            .start();
        try (okhttp3.Response resp = call(request(server.uri().resolve("/openapi.json")))) {
            JSONObject json = new JSONObject(resp.body().string());
            assertThat(json.getJSONArray("tags").toList().stream()
                    .map(Map.class::cast)
                    .map(v -> (String) v.get("name"))
                    .collect(Collectors.toList()),
                contains("Apple", "Banana", "Carrot"));
        }

    }

    @After
    public void cleanup() {
        MuAssert.stopAndCheck(server);
    }

}
