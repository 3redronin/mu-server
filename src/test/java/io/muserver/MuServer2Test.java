package io.muserver;

import org.junit.After;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class MuServer2Test {

    private static final Logger log = LoggerFactory.getLogger(MuServer2Test.class);
    private MuServer server;

    @Test
    public void canStartAndStopHttp() throws Exception {
        for (int i = 0; i < 2; i++) {
            int finalI = i;
            var server = MuServerBuilder.muServer()
                .withHttpPort(10110)
                .addHandler(Method.GET, "/blah", (request, response, pathParams) -> {
                    response.write("Hello " + finalI);
                })
                .start2();
            log.info("Started at " + server.uri());

            try (var resp = call(request(server.uri().resolve("/blah")))) {
                assertThat(resp.code(), equalTo(200));
                assertThat(resp.body().string(), equalTo("Hello " + i));
            }
            server.stop();
        }
    }

    @Test
    public void canStartAndStopHttps() throws Exception {
        for (int i = 0; i < 2; i++) {
            int finalI = i;
            var server = MuServerBuilder.muServer()
                .withHttpsPort(10100)
                .addHandler(Method.GET, "/blah", (request, response, pathParams) -> {
                    response.write("Hello " + finalI);
                })
                .start2();
            log.info("Started at " + server.uri());

            try (var resp = call(request(server.uri().resolve("/blah")))) {
                assertThat(resp.code(), equalTo(200));
                assertThat(resp.body().string(), equalTo("Hello " + i));
            }
            server.stop();
        }
    }

    @Test
    public void canChunk() throws Exception {
        server = MuServerBuilder.muServer()
            .withHttpsPort(0)
            .addHandler(Method.GET, "/blah", (request, response, pathParams) -> {
                response.headers().set(HeaderNames.TRAILER, "server-timing");
                response.sendChunk("Hello");
                response.sendChunk(" ");
                response.sendChunk("world");
                response.trailers().set(HeaderNames.SERVER_TIMING, new ParameterizedHeaderWithValue("total", Map.of("dur", "123.4")));
            })
            .start2();
        log.info("Started at " + server.uri());

        try (var resp = call(request(server.uri().resolve("/blah")))) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.body().string(), equalTo("Hello world"));
            assertThat(resp.trailers().get("server-timing"), nullValue());
        }

        try (var resp = call(request(server.uri().resolve("/blah"))
            .header("TE", "trailers")
        )) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.body().string(), equalTo("Hello world"));
            assertThat(resp.trailers().get("server-timing"), equalTo("total;dur=123.4"));
        }

    }

    @Test
    public void canWriteChunksToOutputStream() throws Exception {
        server = MuServerBuilder.muServer()
            .withHttpsPort(10100)
            .addHandler(Method.GET, "/blah", (request, response, pathParams) -> {
                response.headers().set(HeaderNames.TRAILER, "server-timing");
                response.writer().write("Hello");
                response.writer().flush();
                response.writer().write(" ");
                response.writer().flush();
                response.writer().write("world");
                response.trailers().set(HeaderNames.SERVER_TIMING, new ParameterizedHeaderWithValue("total", Map.of("dur", "123.4")));
            })
            .start2();
        log.info("Started at " + server.uri());

        try (var resp = call(request(server.uri().resolve("/blah")))) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.headers("content-type"), contains("text/plain;charset=utf-8"));
            assertThat(resp.body().string(), equalTo("Hello world"));
            assertThat(resp.trailers().get("server-timing"), nullValue());
        }

        try (var resp = call(request(server.uri().resolve("/blah"))
            .header("TE", "trailers")
        )) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.headers("content-type"), contains("text/plain;charset=utf-8"));
            assertThat(resp.body().string(), equalTo("Hello world"));
            assertThat(resp.trailers().get("server-timing"), equalTo("total;dur=123.4"));
        }

    }

    @Test
    public void canWriteFixedLengthToOutputStream() throws Exception {
        server = MuServerBuilder.muServer()
            .withHttpsPort(10100)
            .addHandler(Method.GET, "/blah", (request, response, pathParams) -> {
                response.headers().set(HeaderNames.TRAILER, "server-timing");
                response.headers().set(HeaderNames.CONTENT_LENGTH, 11);
                response.writer().write("Hello");
                response.writer().flush();
                response.writer().write(" ");
                response.writer().flush();
                response.writer().write("world");
                response.trailers().set(HeaderNames.SERVER_TIMING, new ParameterizedHeaderWithValue("total", Map.of("dur", "123.4")));
            })
            .start2();
        log.info("Started at " + server.uri());

        try (var resp = call(request(server.uri().resolve("/blah")))) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.headers("content-type"), contains("text/plain;charset=utf-8"));
            assertThat(resp.body().string(), equalTo("Hello world"));
            assertThat(resp.trailers().get("server-timing"), nullValue());
        }

        try (var resp = call(request(server.uri().resolve("/blah"))
            .header("TE", "trailers")
        )) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.headers("content-type"), contains("text/plain;charset=utf-8"));
            assertThat(resp.body().string(), equalTo("Hello world"));
            assertThat(resp.trailers().get("server-timing"), nullValue());
        }

    }



    @After
    public void stopIt() {
        if (server != null) {
            server.stop();
        }
    }

}