package io.muserver;

import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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

    @Test
    public void fixedLengthThingsCanBeWrittenAndTheyAreDiscarded() throws Exception {
        server = ServerUtils.httpsServerForTest()
            .addHandler(Method.HEAD, "/", (request, response, pathParams) -> {
                response.write("Hello");
            })
            .start();

        for (int i = 0; i < 2; i++) {
            try (Response resp = call(request(server.uri()).head())) {
                assertThat(resp.code(), is(200));
                assertThat(resp.headers("Content-Type"), contains("text/plain;charset=utf-8"));
                assertThat(resp.headers("Content-Length"), contains("5"));
                assertThat(resp.body().contentLength(), is(0L));
            }
        }
    }

    @Test
    public void chunkedThingsCanBeWrittenAndTheyAreDiscarded() throws Exception {
        server = ServerUtils.httpsServerForTest()
            .addHandler(Method.HEAD, "/", (request, response, pathParams) -> {
                response.contentType("text/plain;charset=utf-8");
                try (var outputStream = response.writer()) {
                    outputStream.write("Hello");
                    outputStream.flush();
                    outputStream.write("world");
                }
            })
            .start();
        for (int i = 0; i < 2; i++) {
            try (Response resp = call(request(server.uri()).head())) {
                assertThat(resp.code(), is(200));
                assertThat(resp.headers("Content-Type"), contains("text/plain;charset=utf-8"));
                assertThat(resp.headers("Content-Length"), empty());
                assertThat(resp.headers("Transfer-Encoding"), contains("chunked"));
                assertThat(resp.body().contentLength(), is(0L));
            }
        }
    }


    @AfterEach
    public void stopIt() {
        scaffolding.MuAssert.stopAndCheck(server);
    }
}

