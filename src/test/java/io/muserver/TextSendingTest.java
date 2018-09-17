package io.muserver;

import okhttp3.Response;
import org.junit.After;
import org.junit.Test;
import scaffolding.MuAssert;
import scaffolding.StringUtils;

import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class TextSendingTest {

    private MuServer server;

    @Test
    public void largeChunksOfTextCanBeWritten() throws Exception {
        String lotsoText = StringUtils.randomStringOfLength(70000);
        server = MuServerBuilder.httpServer()
            .withGzipEnabled(false)
            .addHandler(Method.GET, "/", (request, response, pp) -> {
                response.contentType(ContentTypes.TEXT_PLAIN);
                response.write(lotsoText);
            }).start();

        try (Response resp = call(request().url(server.httpUri().toString()))) {
            assertThat(resp.header("Content-Length"), is(String.valueOf(lotsoText.getBytes(UTF_8).length)));
            assertThat(resp.header("Transfer-Encoding"), is(nullValue()));
            assertThat(resp.body().string(), equalTo(lotsoText));
        }
    }

    @Test
    public void emptyStringsAreFine() throws Exception {
        server = MuServerBuilder.httpServer()
            .addHandler(Method.GET, "/", (request, response, pp) -> {
                response.contentType(ContentTypes.TEXT_PLAIN);
                response.write("");
            }).start();

        try (Response resp = call(request().url(server.httpUri().toString()))) {
            assertThat(resp.header("Content-Length"), is("0"));
            assertThat(resp.body().string(), equalTo(""));
        }
    }

    @Test
    public void textCanBeSentInChunks() throws Exception {
        List<String> chunks = asList("Hello", "World", StringUtils.randomStringOfLength(200000), "Yo");

        server = MuServerBuilder.httpServer()
            .addHandler(Method.GET, "/", (request, response, pp) -> {
                response.contentType(ContentTypes.TEXT_PLAIN);
                for (String chunk : chunks) {
                    response.sendChunk(chunk);
                }
            }).start();

        String expected = String.join("", chunks);

        try (Response resp = call(request().url(server.httpUri().toString()))) {
            assertThat(resp.header("Content-Length"), is(nullValue()));
            assertThat(resp.header("Transfer-Encoding"), is("chunked"));
            assertThat(resp.body().string(), equalTo(expected));
        }
    }

    @Test
    public void anEmptyHandlerIsA200WithNoContent() throws Exception {
        server = MuServerBuilder.httpServer()
            .addHandler(Method.GET, "/", (request, response, pp) -> {
            }).start();
        try (Response resp = call(request().url(server.httpUri().toString()))) {
            assertThat(resp.header("Content-Length"), is("0"));
            assertThat(resp.body().bytes().length, is(0));
        }
    }


    @After
    public void stopIt() {
        MuAssert.stopAndCheck(server);
    }
}
