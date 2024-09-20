package io.muserver;

import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;
import io.muserver.handlers.ResourceType;
import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import scaffolding.MuAssert;
import scaffolding.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.zip.GZIPInputStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;
import static scaffolding.ServerUtils.httpsServerForTest;

public class CustomEncoderTest {
    private static final String LOTS_OF_TEXT = StringUtils.randomAsciiStringOfLength(20000);
    private MuServer server;

    private static class ZstdEncoder implements ContentEncoder {

        @Override
        public String contentCoding() {
            return "zstd";
        }

        @Override
        public boolean prepare(MuRequest request, MuResponse response) {
            return ContentEncoder.defaultPrepare(request, response, ResourceType.gzippableMimeTypes(ResourceType.getResourceTypes()), 1400L, contentCoding());
        }

        @Override
        public OutputStream wrapStream(MuRequest request, MuResponse response, OutputStream stream) throws IOException {
            return new ZstdOutputStream(stream);
        }
    }

    @Test
    public void responseWriteCanBeGZipped() throws IOException {
        server = httpsServerForTest()
            .withContentEncoders(List.of(new ZstdEncoder(), GZIPEncoderBuilder.gzipEncoder().build()))
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.contentType(ContentTypes.TEXT_PLAIN_UTF8);
                response.write(LOTS_OF_TEXT);
            })
            .start();
        compareCompressedVsNotCompressed("/");
    }


    private void compareCompressedVsNotCompressed(String path) throws IOException {
        String unzipped;
        try (var resp = call(request(server.uri().resolve(path)).header("Accept-Encoding", "hmm, gzip, zstd, deflate"))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.headers("content-encoding"), contains("zstd"));
            assertThat(resp.headers("vary"), contains(containsString("accept-encoding")));
            try (ByteArrayOutputStream boas = new ByteArrayOutputStream();
                 InputStream is = new ZstdInputStream(resp.body().byteStream())) {
                Mutils.copy(is, boas, 8192);
                unzipped = boas.toString(UTF_8);
            }
        }

        try (Response resp = call(request(server.uri().resolve(path)).header("Accept-Encoding", "invalid"))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.header("content-encoding"), is(nullValue()));
            assertThat(resp.headers("vary"), contains(containsString("accept-encoding")));
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

    @AfterEach
    public void stopIt() {
        MuAssert.stopAndCheck(server);
    }
}
