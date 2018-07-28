package io.muserver;

import okhttp3.Headers;
import okhttp3.*;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

import static io.muserver.MuServerBuilder.httpsServer;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class UploadTest {

    private MuServer server;
    public static File guangzhou = new File("src/test/resources/sample-static/images/guangzhou.jpeg");

    @Before
    public void check() throws IOException {
        if (!guangzhou.exists()) {
            Assert.fail("Could not find an image at " + guangzhou.getCanonicalPath());
        }
    }

    @Test
    public void filesCanBeUploadedAlongSideFormParams() throws IOException {

        server = httpsServer()
            .addHandler(Method.POST, "/upload", (request, response, pathParams) -> {
                response.sendChunk(request.form().get("Hello")
                    + "\n" + request.form().get("The name"));
                boolean twoWaysToGetFileIsSame = request.uploadedFiles("image").get(0).filename().equals(request.uploadedFile("image").filename());
                response.sendChunk("\ntwoWaysToGetFileIsSame=" + twoWaysToGetFileIsSame + "\n");

                UploadedFile image = request.uploadedFile("image");
                response.sendChunk(image.filename() + " is " + image.asBytes().length + " bytes");

                response.sendChunk("\nnon-existent: " + request.uploadedFile("nothing"));

            }).start();

        try (Response resp = call(request()
            .url(server.uri().resolve("/upload").toString())
            .post(new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("Hello", "World")
                .addFormDataPart("The name", "the value / with / stuff")
                .addPart(Headers.of("Content-Disposition", "form-data; name=\"image\"; filename=\"guangzhou.jpeg\""),
                    RequestBody.create(MediaType.parse("image/jpeg"), guangzhou))
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

    @After
    public void stopIt() {
        scaffolding.MuAssert.stopAndCheck(server);
    }

}
