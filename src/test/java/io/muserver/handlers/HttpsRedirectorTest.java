package io.muserver.handlers;

import io.muserver.Method;
import io.muserver.MuServer;
import okhttp3.Response;
import okhttp3.internal.Util;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static io.muserver.MuServerBuilder.muServer;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThat;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class HttpsRedirectorTest {

    private MuServer server;

    @Before
    public void setup() {
        server = muServer()
            .withHttpPort(0)
            .withHttpsPort(12443)
            .addHandler(
                HttpsRedirectorBuilder.toHttpsPort(12443)
                    .withHSTSExpireTime(365, TimeUnit.DAYS)
                    .includeSubDomains(true)
            )
            .addHandler((request, response) -> {
                response.write("Uri is " + request.uri());
                return true;
            })
            .start();
    }

    @Test
    public void doesRedirection() throws IOException {
        String newLocation;
        try (Response resp = call(request().url(server.httpUri().toString()))) {
            assertThat(resp.code(), is(302));
            newLocation = resp.header("Location");
        }
        try (Response resp = call(request().url(newLocation))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), equalTo("Uri is https://localhost:12443/"));
            assertThat(resp.header("Strict-Transport-Security"), equalTo("max-age=31536000; includeSubDomains"));
        }

    }

    @Test
    public void returnsA400IfMethodIsNotGetOrHead() throws IOException {
        for (Method method : Method.values()) {
            if (method == Method.GET || method == Method.HEAD) {
                continue;
            }
            try (Response resp = call(request()
                .url(server.httpUri().toString())
                .method(method.name(), Util.EMPTY_REQUEST)
            )) {
                assertThat(resp.code(), is(400));
                assertThat(resp.body().string(), containsString("HTTP is not supported for this endpoint. Please use the HTTPS endpoint at " + server.httpsUri()));
                assertThat(resp.header("Strict-Transport-Security"), is(nullValue()));
            }
        }
    }

    @After
    public void destroy() {
        scaffolding.MuAssert.stopAndCheck(server);
    }
}