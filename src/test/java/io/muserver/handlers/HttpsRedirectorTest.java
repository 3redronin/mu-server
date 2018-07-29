package io.muserver.handlers;

import io.muserver.MuServer;
import io.muserver.MuServerBuilder;
import okhttp3.Response;
import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class HttpsRedirectorTest {

    private MuServer server;

    @Test
    public void doesRedirection() throws IOException {
        server = MuServerBuilder.muServer()
            .withHttpPort(12380)
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

        String newLocation;
        try (Response resp = call(request().url("http://localhost:12380/"))) {
            assertThat(resp.code(), is(302));
            newLocation = resp.header("Location");
        }
        try (Response resp = call(request().url(newLocation))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), equalTo("Uri is https://localhost:12443/"));
            assertThat(resp.header("Strict-Transport-Security"), equalTo("max-age=31536000; includeSubDomains"));
        }

    }


    @After
    public void destroy() {
        scaffolding.MuAssert.stopAndCheck(server);
    }
}