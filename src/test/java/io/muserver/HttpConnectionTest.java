package io.muserver;

import org.junit.After;
import org.junit.Test;
import scaffolding.ServerUtils;

import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.muserver.MuServerBuilder.httpServer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class HttpConnectionTest {

    private MuServer server;

    @Test
    public void itKnowsHTTPIsNotHTTPS() {
        AtomicReference<Throwable> error = new AtomicReference<>();
        server = httpServer()
            .addHandler(Method.GET,  "/", (request, response, pathParams) -> {
                try {
                    HttpConnection con = request.connection();
                    assertThat(con.protocol(), is("HTTP/1.1"));
                    assertThat(con.isHttps(), is(false));
                    assertThat(con.httpsProtocol(), is(nullValue()));
                    assertThat(con.cipher(), is(nullValue()));
                } catch (Throwable t) {
                    error.set(t);
                }
            })
            .start();

        call(request(server.uri())).close();
        assertThat(error.get(), is(nullValue()));
    }

    @Test
    public void itKnowsHTTPSStuff() {
        AtomicReference<Throwable> error = new AtomicReference<>();
        server = ServerUtils.httpsServerForTest()
            .withHttpsConfig(HttpsConfigBuilder.unsignedLocalhost().withProtocols("TLSv1.2"))
            .addHandler(Method.GET,  "/", (request, response, pathParams) -> {
                try {
                    HttpConnection con = request.connection();
                    assertThat(con.protocol(), oneOf("HTTP/1.1", "HTTP/2"));
                    assertThat(con.isHttps(), is(true));
                    assertThat(con.httpsProtocol(), is("TLSv1.2"));
                    assertThat(con.cipher(), is(not(nullValue())));
                } catch (Throwable t) {
                    error.set(t);
                }
            })
            .start();
        call(request(server.uri())).close();
        assertThat(error.get(), is(nullValue()));
    }

    @Test
    public void thereIsGenericStuffAndStats() {
        AtomicReference<Throwable> error = new AtomicReference<>();
        server = ServerUtils.httpsServerForTest()
            .withRateLimiter(request -> RateLimit.builder()
                .withBucket(request.relativePath())
                .withRate(1)
                .withWindow(1, TimeUnit.HOURS)
                .build())
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                try {
                    HttpConnection con = request.connection();
                    assertThat(con.startTime().toEpochMilli(), lessThanOrEqualTo(Instant.now().toEpochMilli()));
                    assertThat(con.remoteAddress().getAddress().getHostAddress(), is("127.0.0.1"));

                    assertThat(con.activeRequests(), contains(request));
                    assertThat(con.completedRequests(), is(1L));
                    assertThat(con.invalidHttpRequests(), is(0L));
                    assertThat(con.rejectedDueToOverload(), is(2L));
                    assertThat(con.activeWebsockets(), is(empty()));

                    assertThat(request.server().activeConnections(), hasItems(con));

                } catch (Throwable t) {
                    error.set(t);
                }
            })
            .start();
        call(request(server.uri().resolve("/blah"))).close();
        call(request(server.uri().resolve("/blah"))).close();
        call(request(server.uri().resolve("/blah"))).close();
        call(request(server.uri())).close();
        assertThat(error.get(), is(nullValue()));
    }

    @After
    public void stopIt() {
        scaffolding.MuAssert.stopAndCheck(server);
    }

}
