package io.muserver;

import okhttp3.Response;
import org.junit.After;
import org.junit.Test;
import scaffolding.MuAssert;
import scaffolding.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

import static io.muserver.handlers.ResourceHandlerBuilder.classpathHandler;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;
import static scaffolding.ServerUtils.httpsServerForTest;

public class GzipTest {
    private static final String LOTS_OF_TEXT = StringUtils.randomAsciiStringOfLength(20000);
    private MuServer server;

    @Test
    public void resourcesCanBeGzipped() throws IOException {
        server = httpsServerForTest()
            .addHandler(classpathHandler("/sample-static"))
            .start();
        compareZippedVsNotZipped("/overview.txt");
    }

    @Test
    public void responseWriteCanBeGZipped() throws IOException {
        server = httpsServerForTest()
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.contentType(ContentTypes.TEXT_PLAIN_UTF8);
                response.write(LOTS_OF_TEXT);
            })
            .start();
        compareZippedVsNotZipped("/");
    }

    @Test
    public void asyncWritesCanBeGzipped() throws IOException {
        server = httpsServerForTest()
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.contentType(ContentTypes.TEXT_PLAIN_UTF8);
                AsyncHandle asyncHandle = request.handleAsync();
                asyncHandle.write(Mutils.toByteBuffer("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"));
                asyncHandle.write(Mutils.toByteBuffer(LOTS_OF_TEXT));
                asyncHandle.complete();
            })
            .start();
        compareZippedVsNotZipped("/");
    }

    @Test
    public void thatWhichIsEncodedShallNotBeEncodedAgain() throws IOException {
        server = httpsServerForTest()
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

    @Test
    public void sendChunkCanBeGzipped() throws IOException {
        String someText = StringUtils.randomAsciiStringOfLength(800);
        server = httpsServerForTest()
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.contentType(ContentTypes.TEXT_PLAIN_UTF8);
                for (int i = 0; i < 20; i++) {
                    response.sendChunk(i + someText + i);
                }
            })
            .start();
        compareZippedVsNotZipped("/");
    }

    private void compareZippedVsNotZipped(String path) throws IOException {
        String unzipped;
        try (Response resp = call(request(server.uri().resolve(path)).header("Accept-Encoding", "hmm, gzip, deflate"))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.headers("content-encoding"), contains("gzip"));
            assertThat(resp.headers("vary"), contains("accept-encoding"));
            try (ByteArrayOutputStream boas = new ByteArrayOutputStream();
                 InputStream is = new GZIPInputStream(resp.body().byteStream())) {
                Mutils.copy(is, boas, 8192);
                unzipped = boas.toString("UTF-8");
            }
        }

        try (Response resp = call(request(server.uri().resolve(path)).header("Accept-Encoding", "invalid"))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.header("content-encoding"), is(nullValue()));
            assertThat(resp.headers("vary"), contains("accept-encoding"));
            assertThat(resp.body().string(), equalTo(unzipped));
        }
    }

    @Test
    public void ifTheResponseIsAlreadyCompressedThenDoNotRecompress() throws IOException {
        server = httpsServerForTest()
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
            assertThat(resp.headers("vary"), contains("accept-encoding"));

            try (ByteArrayOutputStream boas = new ByteArrayOutputStream();
                 InputStream is = new GZIPInputStream(resp.body().byteStream())) {
                Mutils.copy(is, boas, 8192);
                String unzipped = boas.toString("UTF-8");
                assertThat(unzipped, startsWith("<!doctype html>"));
            }
        }
    }

    @After
    public void stopIt() {
        MuAssert.stopAndCheck(server);
    }
}
