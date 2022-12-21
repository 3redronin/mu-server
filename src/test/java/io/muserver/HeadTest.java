package io.muserver;

import okhttp3.Response;
import org.junit.After;
import org.junit.Test;
import scaffolding.ServerUtils;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class HeadTest {
    private MuServer server;

    @Test
    public void headWithNoContentLengthCanReturn200() throws Exception {
        server = ServerUtils.httpsServerForTest()
            .addHandler(Method.HEAD, "/", (request, response, pathParams) -> {
                response.status(200);
                response.contentType("application/custom");
            })
            .start();

        try (Response resp = call(request(server.uri()).head())) {
            assertThat(resp.code(), is(200));
            assertThat(resp.headers("Content-Type"), contains("application/custom"));
            assertThat(resp.headers("Content-Length"), empty());
            assertThat(resp.body().contentLength(), is(0L));
        }
    }

    @Test
    public void headWithContentLengthCanReturn200() throws Exception {
        server = ServerUtils.httpsServerForTest()
            .addHandler(Method.HEAD, "/", (request, response, pathParams) -> {
                response.status(200);
                response.contentType("application/custom");
                response.headers().set("content-length", 2002);
            })
            .start();

        try (Response resp = call(request(server.uri()).head())) {
            assertThat(resp.code(), is(200));
            assertThat(resp.headers("Content-Type"), contains("application/custom"));
            assertThat(resp.headers("Content-Length"), contains("2002"));
            assertThat(resp.body().contentLength(), is(0L));
        }
    }

    @Test
    public void headReturns200ByDefault() throws Exception {
        server = ServerUtils.httpsServerForTest()
            .addHandler(Method.HEAD, "/", (request, response, pathParams) -> {
            })
            .start();

        try (Response resp = call(request(server.uri()).head())) {
            assertThat(resp.code(), is(200));
            assertThat(resp.headers("Content-Type"), empty());
            assertThat(resp.headers("Content-Length"), empty());
            assertThat(resp.body().contentLength(), is(0L));
        }
    }

    @After
    public void stopIt() {
        scaffolding.MuAssert.stopAndCheck(server);
    }
}

