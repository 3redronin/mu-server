package io.muserver;

import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import scaffolding.MuAssert;
import scaffolding.ServerUtils;
import scaffolding.StringUtils;

import java.io.IOException;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static scaffolding.ClientUtils.*;

public class TextSendingTest {

    private MuServer server;

    @Test
    public void largeChunksOfTextCanBeWritten() throws Exception {
        String lotsoText = StringUtils.randomStringOfLength(70000);
        server = ServerUtils.httpsServerForTest()
            .withGzipEnabled(false)
            .addHandler(Method.GET, "/", (request, response, pp) -> {
                response.contentType(ContentTypes.TEXT_PLAIN);
                response.write(lotsoText);
            }).start();

        try (Response resp = call(request(server.uri()))) {
            assertThat(resp.header("Content-Length"), is(String.valueOf(lotsoText.getBytes(UTF_8).length)));
            assertThat(resp.header("Transfer-Encoding"), is(nullValue()));
            assertThat(resp.body().string(), equalTo(lotsoText));
        }
    }

    @Test
    public void emptyStringsAreFine() throws Exception {
        server = ServerUtils.httpsServerForTest()
            .addHandler(Method.GET, "/", (request, response, pp) -> {
                response.contentType(ContentTypes.TEXT_PLAIN);
                response.write("");
            }).start();

        try (Response resp = call(request(server.uri()))) {
            assertThat(resp.header("Content-Length"), is("0"));
            assertThat(resp.body().string(), equalTo(""));
        }
    }

    @Test
    public void textCanBeSentInChunks() throws Exception {
        List<String> chunks = asList("Hello", "World", StringUtils.randomStringOfLength(200000), "Yo");

        server = ServerUtils.httpsServerForTest()
            .addHandler(Method.GET, "/", (request, response, pp) -> {
                response.contentType(ContentTypes.TEXT_PLAIN);
                for (String chunk : chunks) {
                    response.sendChunk(chunk);
                }
            }).start();

        String expected = String.join("", chunks);

        try (Response resp = call(request(server.uri()))) {
            assertThat(resp.header("Content-Length"), is(nullValue()));
            if (isHttp2(resp)) {
                assertThat(resp.header("Transfer-Encoding"), is(nullValue()));
            } else {
                assertThat(resp.header("Transfer-Encoding"), is("chunked"));
            }
            assertThat(resp.body().string(), equalTo(expected));
        }
    }

    @Test
    public void anEmptyHandlerIsA200WithNoContent() throws Exception {
        server = ServerUtils.httpsServerForTest()
            .addHandler(Method.GET, "/", (request, response, pp) -> {
            }).start();
        try (Response resp = call(request(server.uri()))) {
            assertThat(resp.header("Content-Length"), is("0"));
            assertThat(resp.body().bytes().length, is(0));
        }
    }
    
    @Test
    public void defaultsToTextPlainIfNoContentTypeSet() throws IOException {
        server = ServerUtils.httpsServerForTest()
            .addHandler(Method.GET, "/html", (request, response, pp) -> {
                response.contentType("text/html");
                response.write("This is HTML");
            })
            .addHandler(Method.GET, "/text", (request, response, pp) -> {
                response.write("This is text");
            })
            .start();
        try (Response resp = call(request(server.uri().resolve("/html")))) {
            assertThat(resp.header("Content-Type"), is("text/html"));
            assertThat(resp.body().string(), is("This is HTML"));
        }
        try (Response resp = call(request(server.uri().resolve("/text")))) {
            assertThat(resp.header("Content-Type"), is("text/plain;charset=utf-8"));
            assertThat(resp.body().string(), is("This is text"));
        }
    }


    @AfterEach
    public void stopIt() {
        MuAssert.stopAndCheck(server);
    }
}
