package io.muserver;

import org.junit.After;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static io.muserver.MuServerBuilder.httpsServer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThrows;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class MuServer2Test {

    private static final Logger log = LoggerFactory.getLogger(MuServer2Test.class);
    private MuServer server;

    @Test
    public void canStartAndStopHttp() throws Exception {
        var s = "Hello ".repeat(1000);
        for (int i = 0; i < 2; i++) {
            int finalI = i;
            var server = MuServerBuilder.muServer()
                .withHttpPort(0) // todo reuse same port and make this work
                .addHandler(Method.GET, "/blah", (request, response, pathParams) -> {
                    response.write("Hello " + s + finalI);
                })
                .start2();
            log.info("Started at " + server.uri());

            try (var resp = call(request(server.uri().resolve("/blah")))) {
                assertThat(resp.code(), equalTo(200));
                assertThat(resp.body().string(), equalTo("Hello " + s + i));
            }
            server.stop();
        }
    }

    @Test
    public void canStartAndStopHttps() throws Exception {
        for (int i = 0; i < 1; i++) {
            int finalI = i;
            var server = MuServerBuilder.muServer()
                .withHttpsPort(0)
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
    public void tls12Available() throws Exception {
        var theCipher = "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384";
        server = httpsServer()
            .withHttpsConfig(HttpsConfigBuilder.httpsConfig()
                .withProtocols("TLSv1.2")
                .withCipherFilter((supportedCiphers, defaultCiphers) -> {
                    return List.of(theCipher);
                })
            )
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                HttpConnection con = request.connection();
                response.write(con.isHttps() + " " + con.httpsProtocol() + " " + con.cipher());
            })
            .start2();
        try (var resp = call(request(server.uri().resolve("/")))) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.body().string(), equalTo("true TLSv1.2 " + theCipher));
        }
    }

    @Test
    public void canGetServerInfo() throws Exception {
        var theCipher = "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384";
        server = httpsServer()
            .withHttpsConfig(HttpsConfigBuilder.httpsConfig()
                .withProtocols("TLSv1.2")
                .withCipherFilter((supportedCiphers, defaultCiphers) -> List.of(theCipher))
            )
            .start2();
        assertThat(server.sslInfo().providerName(), not(nullValue()));
        assertThat(server.sslInfo().ciphers(), contains(theCipher));
        assertThat(server.sslInfo().protocols(), contains("TLSv1.2"));
        assertThat(server.sslInfo().certificates(), hasSize(1));
    }


    @Test
    public void ifNoCommonCiphersThenItDoesNotLoad() throws Exception {
        var theCipher = "TLS_AES_128_GCM_SHA256";
        server = httpsServer()
            .withHttpsConfig(HttpsConfigBuilder.httpsConfig()
                .withProtocols("TLSv1.2")
                .withCipherFilter((supportedCiphers, defaultCiphers) -> List.of(theCipher))
            )
            .start2();
        assertThrows(UncheckedIOException.class, () -> {
            try (var ignored = call(request(server.uri().resolve("/")))) {
            }
        });
        assertThat(server.stats().failedToConnect(), equalTo(1L));
    }

    @Test
    public void tls13Available() throws Exception {
        AtomicReference<String> theCipher = new AtomicReference<>();
        server = httpsServer()
            .withHttpsConfig(HttpsConfigBuilder.httpsConfig()
                .withProtocols("TLSv1.2", "TLSv1.3")
                .withCipherFilter((supportedCiphers, defaultCiphers) -> {
                    theCipher.set(defaultCiphers.get(0));
                    return List.of(theCipher.get());
                })
            )
            .addHandler(Method.GET, "/", new RouteHandler() {
                @Override
                public void handle(MuRequest request, MuResponse response, Map<String, String> pathParams) throws Exception {
                    HttpConnection con = request.connection();
                    response.write(con.isHttps() + " " + con.httpsProtocol() + " " + con.cipher());
                }
            })
            .start2();
        try (var resp = call(request(server.uri().resolve("/")))) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.body().string(), equalTo("true TLSv1.3 " + theCipher.get()));
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
            .withHttpsPort(0)
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
            .withHttpsPort(0)
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