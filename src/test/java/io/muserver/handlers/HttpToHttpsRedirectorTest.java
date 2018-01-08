package io.muserver.handlers;

import okhttp3.OkHttpClient;
import okhttp3.Response;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import io.muserver.Method;
import io.muserver.MuServer;
import io.muserver.SSLContextBuilder;

import java.io.IOException;
import java.net.URI;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static io.muserver.MuServerBuilder.httpServer;
import static io.muserver.MuServerBuilder.muServer;
import static scaffolding.ClientUtils.request;
import static scaffolding.ClientUtils.sslContextForTesting;
import static scaffolding.ClientUtils.veryTrustingTrustManager;

public class HttpToHttpsRedirectorTest {
    private MuServer server;
    private OkHttpClient client;

    @Before public void setupClient() {
        client = new OkHttpClient.Builder()
            .hostnameVerifier((hostname, session) -> true)
            .sslSocketFactory(sslContextForTesting(veryTrustingTrustManager).getSocketFactory(), veryTrustingTrustManager)
            .followRedirects(false)
            .followSslRedirects(false)
            .build();
    }

    @Test public void httpRequestsAreRedirectedToTheHttpsUrl() throws IOException {
        server = muServer()
            .withHttpConnection(11080)
            .withHttpsConnection(11443, SSLContextBuilder.unsignedLocalhostCert())
            .addAsyncHandler(new HttpToHttpsRedirector(11443))
            .addHandler(Method.GET, "/blah", (request, response) -> {
                response.write("Hello from " + request.uri());
                return false; // I don't understand :'(
            }).start();

        Response resp = get("/blah?ha=yo");
        assertThat(resp.code(), is(302));
        assertThat(resp.header("Location"), is("https://localhost:11443/blah?ha=yo"));
    }

    @Test public void relativeRedirectsGetConvertedToAbsoluteOnes() throws IOException {
        // this test doesn't belong here, but hey, the client was handy
        server = httpServer().addHandler((request, response) -> {
                response.redirect("#hashish");
                return true;
            }).start();

        String str = "/some?value=hey";
        Response resp = get(str);
        assertThat(resp.code(), is(302));
        assertThat(resp.header("Location"), is(resolve(str) + "#hashish"));
    }

    @After public void stopIt() {
        server.stop();
    }

    Response get(String str) throws IOException {
        return client.newCall(request().url(resolve(str).toURL()).build()).execute();
    }

    URI resolve(String str) {
        return server.httpUri().resolve(str);
    }
}