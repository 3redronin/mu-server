package io.muserver;

import io.muserver.rest.RestHandlerBuilder;
import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import scaffolding.MuAssert;
import scaffolding.StringUtils;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.StreamingOutput;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import static io.muserver.handlers.ResourceHandlerBuilder.classpathHandler;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;
import static scaffolding.ServerUtils.testServer;

public class GzipTest {
    private static final String LOTS_OF_TEXT = StringUtils.randomAsciiStringOfLength(20000);
    private MuServer server;

    @ParameterizedTest
    @ValueSource(strings = { "http", "https" })
    public void resourcesCanBeGzipped(String type) throws IOException {
        server = testServer(type)
            .addHandler(classpathHandler("/sample-static"))
            .start();
        compareZippedVsNotZipped("/overview.txt");
    }

    @ParameterizedTest
    @ValueSource(strings = { "http", "https" })
    public void responseWriteCanBeGZipped(String type) throws IOException {
        server = testServer(type)
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.contentType(ContentTypes.TEXT_PLAIN_UTF8);
                response.write(LOTS_OF_TEXT);
            })
            .start();
        compareZippedVsNotZipped("/");
    }

    @ParameterizedTest
    @ValueSource(strings = { "http", "https" })
    public void ifMimeTypesDoNotHaveResponseThenThereIsNoGzipping(String type) throws IOException {
        server = testServer(type)
            .withGzip(0, Collections.singleton("text/html"))
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.contentType(ContentTypes.TEXT_PLAIN_UTF8);
                response.write(LOTS_OF_TEXT);
            })
            .start();
        try (Response resp = call(request(server.uri().resolve("/")).header("Accept-Encoding", "invalid"))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.header("content-encoding"), is(nullValue()));
            assertThat(resp.header("vary"), is(nullValue()));
            assertThat(resp.body().string(), equalTo(LOTS_OF_TEXT));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "http", "https" })
    public void asyncWritesCanBeGzipped(String type) throws IOException {
        server = testServer(type)
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.contentType(ContentTypes.TEXT_PLAIN_UTF8);
                AsyncHandle asyncHandle = request.handleAsync();
                asyncHandle.write(Mutils.toByteBuffer("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")).get(10, TimeUnit.SECONDS);
                asyncHandle.write(Mutils.toByteBuffer(LOTS_OF_TEXT)).get(10, TimeUnit.SECONDS);
                asyncHandle.complete();
            })
            .start();
        compareZippedVsNotZipped("/");
    }

    @ParameterizedTest
    @ValueSource(strings = { "http", "https" })
    public void thatWhichIsEncodedShallNotBeEncodedAgain(String type) throws IOException {
        server = testServer(type)
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.contentType(ContentTypes.TEXT_PLAIN_UTF8);
                response.headers().set(HeaderNames.CONTENT_ENCODING, "identity");
                response.write(LOTS_OF_TEXT);
            })
            .start();
        try (Response resp = call(request(server.uri().resolve("/")).header("Accept-Encoding", "umm,gzip"))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.headers("content-encoding"), contains("identity"));
            assertThat(resp.headers("content-length"), contains(String.valueOf(LOTS_OF_TEXT.getBytes(UTF_8).length)));
            assertThat(resp.body().string(), equalTo(LOTS_OF_TEXT));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "http", "https" })
    public void sendChunkCanBeGzipped(String type) throws IOException {
        String someText = StringUtils.randomAsciiStringOfLength(800);
        server = testServer(type)
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.contentType(ContentTypes.TEXT_PLAIN_UTF8);
                for (int i = 0; i < 20; i++) {
                    response.sendChunk(i + someText + i);
                }
            })
            .start();
        compareZippedVsNotZipped("/");
    }

    @ParameterizedTest
    @ValueSource(strings = { "http", "https" })
    public void jaxRSResponsesAreGZipped(String type) throws IOException {
        String someText = StringUtils.randomAsciiStringOfLength(8192);
        @Path("/strings")
        @Produces("application/json")
        class StringResource {
            @GET
            public String get() {
                return someText;
            }

            @GET
            @Path("streamed")
            public StreamingOutput streamed() {
                return output -> output.write(someText.getBytes(UTF_8));
            }

        }
        server = testServer(type)
            .addHandler(RestHandlerBuilder.restHandler(new StringResource()))
            .start();
        compareZippedVsNotZipped("/strings");
        compareZippedVsNotZipped("/strings/streamed");
    }

    private void compareZippedVsNotZipped(String path) throws IOException {
        String unzipped;
        try (Response resp = call(request(server.uri().resolve(path)).header("Accept-Encoding", "hmm, gzip, deflate"))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.headers("content-encoding"), contains("gzip"));
            assertThat(resp.headers("vary"), contains(containsString("accept-encoding")));
            try (ByteArrayOutputStream boas = new ByteArrayOutputStream();
                 InputStream is = new GZIPInputStream(resp.body().byteStream())) {
                Mutils.copy(is, boas, 8192);
                unzipped = boas.toString(UTF_8);
            }
        }

        try (Response resp = call(request(server.uri().resolve(path)).header("Accept-Encoding", "unsupported-encoding-scheme"))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.header("content-encoding"), is(nullValue()));
            assertThat(resp.headers("vary"), contains(containsString("accept-encoding")));
            assertThat(resp.body().string(), equalTo(unzipped));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "http", "https" })
    public void ifTheResponseIsAlreadyCompressedThenDoNotRecompress(String type) throws IOException {
        server = testServer(type)
            .addHandler(Method.GET, "/overview.txt", (request, response, pathParams) -> {
                response.headers().set(HeaderNames.CONTENT_TYPE, ContentTypes.TEXT_PLAIN_UTF8);
                response.headers().set(HeaderNames.CONTENT_ENCODING, "gzip");
                InputStream in = getClass().getResourceAsStream("/sample-static/overview.txt.gz");
                Mutils.copy(in, response.outputStream(), 8192);
            })
            .start();
        try (Response resp = call(request(server.uri().resolve("/overview.txt")).header("Accept-Encoding", "hmm, gzip, deflate"))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.headers("content-type"), contains("text/plain;charset=utf-8"));
            assertThat(resp.headers("content-encoding"), contains("gzip"));
            assertThat(resp.headers("vary").toString(), resp.headers("vary"), contains("accept-encoding"));

            try (ByteArrayOutputStream boas = new ByteArrayOutputStream();
                 InputStream is = new GZIPInputStream(resp.body().byteStream())) {
                Mutils.copy(is, boas, 8192);
                String unzipped = boas.toString("UTF-8");
                assertThat(unzipped, startsWith("<!doctype html>"));
            }
        }
    }

    @AfterEach
    public void stopIt() {
        MuAssert.stopAndCheck(server);
    }
}
