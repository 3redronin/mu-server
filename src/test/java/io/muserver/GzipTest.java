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

import static io.muserver.MuServerBuilder.httpsServer;
import static io.muserver.handlers.ResourceHandlerBuilder.classpathHandler;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class GzipTest {
    public static final String LOTS_OF_TEXT = StringUtils.randomAsciiStringOfLength(20000);
    private MuServer server;

    @Test
    public void resourcesCanBeGzipped() throws IOException {
        server = httpsServer()
            .addHandler(classpathHandler("/sample-static"))
            .start();
        compareZippedVsNotZipped("/overview.txt");
    }

    @Test
    public void responseWriteCanBeGZipped() throws IOException {
        server = httpsServer()
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.contentType(ContentTypes.TEXT_PLAIN_UTF8);
                response.write(LOTS_OF_TEXT);
            })
            .start();
        compareZippedVsNotZipped("/");
    }

    @Test
    public void asyncWritesCanBeGzipped() throws IOException {
        server = httpsServer()
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
    public void sendChunkCanBeGzipped() throws IOException {
        String someText = StringUtils.randomAsciiStringOfLength(800);
        server = httpsServer()
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
        try (Response resp = call(request(server.uri().resolve(path)).header("Accept-Encoding", "gzip"))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.header("content-encoding"), is("gzip"));
            try (ByteArrayOutputStream boas = new ByteArrayOutputStream();
                 InputStream is = new GZIPInputStream(resp.body().byteStream())) {
                Mutils.copy(is, boas, 8192);
                unzipped = boas.toString("UTF-8");
            }
        }

        try (Response resp = call(request(server.uri().resolve(path)).header("Accept-Encoding", "invalid"))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.header("content-encoding"), is(nullValue()));
            assertThat(resp.body().string(), equalTo(unzipped));
        }
    }

    @After
    public void stopIt() {
        MuAssert.stopAndCheck(server);
    }
}
