package io.muserver;

import okhttp3.Headers;
import okhttp3.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import scaffolding.ServerUtils;

import java.io.File;
import java.io.IOException;
import java.util.List;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class UploadTest {

    private MuServer server;
    public static File guangzhou = new File("src/test/resources/sample-static/images/guangzhou.jpeg");
    public static File guangzhouChina = new File("src/test/resources/sample-static/images/guangzhou, china.jpeg");
    public static File friends = new File("src/test/resources/sample-static/images/friends.jpg");

    @BeforeAll
    public static void check() throws IOException {
        if (!guangzhou.exists()) {
            Assertions.fail("Could not find an image at " + guangzhou.getCanonicalPath());
        }
    }

    @Test
    public void filesCanBeUploadedAlongSideFormParams() throws IOException {

        server = ServerUtils.httpsServerForTest()
            .addHandler(Method.POST, "/upload", (request, response, pathParams) -> {
                response.sendChunk(request.form().get("Hello")
                    + "\n" + request.form().get("The name"));
                boolean twoWaysToGetFileIsSame = request.form().uploadedFiles("image").get(0).filename().equals(request.form().uploadedFile("image").filename());
                response.sendChunk("\ntwoWaysToGetFileIsSame=" + twoWaysToGetFileIsSame + "\n");

                UploadedFile image = request.form().uploadedFile("image");
                response.sendChunk(image.filename() + " is " + image.asBytes().length + " bytes");

                response.sendChunk("\nnon-existent: " + request.form().uploadedFile("nothing"));

            }).start();

        try (Response resp = call(request(server.uri().resolve("/upload"))
            .post(new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("Hello", "World")
                .addFormDataPart("The name", "the value / with / stuff")
                .addPart(Headers.of("Content-Disposition", "form-data; name=\"image\"; filename=\"guangzhou.jpeg\""),
                    RequestBody.create(guangzhou, MediaType.parse("image/jpeg")))
                .build())
        )) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), is("World\n" +
                "the value / with / stuff\n" +
                "twoWaysToGetFileIsSame=true\n" +
                "guangzhou.jpeg is 372987 bytes\n" +
                "non-existent: null"));
        }
    }

    @Test
    public void multipleWithSameNameCanBeUploaded() throws IOException {

        server = ServerUtils.httpsServerForTest()
            .addHandler(Method.POST, "/upload", (request, response, pathParams) -> {
                List<UploadedFile> images = request.form().uploadedFiles("image");
                for (UploadedFile image : images) {
                    response.sendChunk(image.filename() + " is " + image.asBytes().length + " bytes\n");
                }
                response.sendChunk("\nnon-existent: " + request.form().uploadedFiles("nothing").size());
            }).start();

        try (Response resp = call(request(server.uri().resolve("/upload"))
            .post(new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addPart(Headers.of("Content-Disposition", "form-data; name=\"image\"; filename=\"guangzhou.jpeg\""),
                    RequestBody.create(guangzhou, MediaType.parse("image/jpeg")))
                .addPart(Headers.of("Content-Disposition", "form-data; name=\"image\"; filename=\"guangzhou, china.jpeg\""),
                    RequestBody.create(guangzhouChina, MediaType.parse("image/jpeg")))
                .addPart(Headers.of("Content-Disposition", "form-data; name=\"image\"; filename=\"friends.jpg\""),
                    RequestBody.create(friends, MediaType.parse("image/jpeg")))
                .build())
        )) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), is("guangzhou.jpeg is 372987 bytes\n" +
                "guangzhou, china.jpeg is 372987 bytes\n" +
                "friends.jpg is 1712954 bytes\n\n" +
                "non-existent: 0"));
        }
    }


    @Test
    public void nothingUploadedResultsInNoFilesAvailable() throws IOException {

        server = ServerUtils.httpsServerForTest()
            .addHandler(Method.POST, "/upload", (request, response, pathParams) -> {
                UploadedFile photo = request.form().uploadedFile("photo");
                List<UploadedFile> photos = request.form().uploadedFiles("photo");
                response.write(photo + " / " + photos.size());
            }).start();
        try (Response resp = call(request(server.uri().resolve("/upload"))
            .post(new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("No", "Upload")
                .build())
        )) {
            assertThat(resp.body().string(), is("null / 0"));
        }
    }

    @Test
    public void requestsAreRejectedIfUploadSizeTooLarge() throws Exception {
        server = ServerUtils.httpsServerForTest()
            .withMaxRequestSize(guangzhou.length() / 2)
            .addHandler(Method.POST, "/upload", (request, response, pathParams) -> {
                try {
                    request.readBodyAsString();
                } catch (IOException e) {
                    throw e;
                }
            }).start();

        try (Response resp = call(request(server.uri().resolve("/upload"))
            .post(new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addPart(Headers.of("Content-Disposition", "form-data; name=\"image\"; filename=\"guangzhou.jpeg\""),
                    RequestBody.create(guangzhou, MediaType.parse("image/jpeg")))
                .build())
        )) {
            assertThat(resp.code(), is(413));
        }
    }

    @AfterEach
    public void stopIt() {
        scaffolding.MuAssert.stopAndCheck(server);
    }

}
