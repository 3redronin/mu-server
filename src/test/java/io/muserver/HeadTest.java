package io.muserver;

import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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


    @ParameterizedTest
    @ValueSource(strings = {"http_outputstream", "https_outputstream", "http_printwriter", "https_printwriter", "http_async", "https_async"})
    public void anyStreamsWrittenToAreIgnoredForHeadRequests(String type) throws Exception {
        server = ServerUtils.testServer(type)
            .withGzipEnabled(false)
            .addHandler(Method.HEAD, "/", (request, response, pathParams) -> {
                response.status(200);
                response.contentType("application/custom");
                switch (type.split("_")[1]) {
                    case "outputstream" -> response.outputStream().write(new byte[1024]);
                    case "printwriter" -> response.writer().write("!".repeat(1024));
                    case "async" -> {
                        AsyncHandle handle = request.handleAsync();
                        handle.write(Mutils.toByteBuffer("!".repeat(1024)), handle::complete);
                    }
                }
            })
            .start();

        try (Response resp = call(request(server.uri()).head())) {
            assertThat(resp.code(), is(200));
            assertThat(resp.headers("Content-Type"), contains("application/custom"));
            assertThat(resp.headers("Transfer-Encoding"), contains("chunked"));
            assertThat(resp.body().contentLength(), is(0L));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"http", "https"})
    public void routeHandlersUseGETRoutes(String type) {
        server = ServerUtils.testServer(type)
            .withGzipEnabled(false)
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.status(200);
                response.contentType("application/custom");
                response.write("!".repeat(2048));
            })
            .start();

        try (Response resp = call(request(server.uri()).head())) {
            assertThat(resp.code(), is(200));
            assertThat(resp.headers("Content-Type"), contains("application/custom"));
            assertThat(resp.headers("Content-Length"), contains("2048"));
            assertThat(resp.body().contentLength(), is(0L));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"http_gzip", "https_gzip", "http_noencoding", "https_noencoding"})
    public void responseWriteIsIgnoredWithHeadRequest(String type) throws Exception {
        boolean gzip = type.split("_")[1].equalsIgnoreCase("gzip");
        server = ServerUtils.testServer(type)
            .withGzipEnabled(gzip)
            .addHandler(Method.HEAD, "/", (request, response, pathParams) -> {
                response.status(200);
                response.write("!".repeat(8192));
            })
            .start();

        try (Response resp = call(request(server.uri()).head())) {
            assertThat(resp.code(), is(200));
            assertThat(resp.headers("Content-Type"), contains("text/plain;charset=utf-8"));
            if (gzip) {
                assertThat(resp.headers("Content-Encoding"), contains("gzip"));
            } else {
                assertThat(resp.headers("Content-Length"), contains("8192"));
                assertThat(resp.headers("Content-Encoding"), empty());
            }
            assertThat(resp.body().contentLength(), is(0L));
        }
    }


    @AfterEach
    public void stopIt() {
        scaffolding.MuAssert.stopAndCheck(server);
    }
}

