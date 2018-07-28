package io.muserver;

import okhttp3.Response;
import org.junit.After;
import org.junit.Test;
import scaffolding.MuAssert;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class HttpsTest {

    private MuServer server;

    @Test public void canCreate() throws Exception {
        server = MuServerBuilder.httpsServer().withHttpsPort(9443).withHttpsConfig(SSLContextBuilder.unsignedLocalhostCert())
            .addHandler((request, response) -> {
                response.contentType(ContentTypes.TEXT_PLAIN);
                response.write("This is encrypted and the URL is " + request.uri());
                return true;
            })
            .start();

        try (Response resp = call(request().url(server.httpsUri().toString()))) {
            assertThat(resp.body().string(), equalTo("This is encrypted and the URL is https://localhost:9443/"));
        }
    }

    @Test public void httpIsNotAvailableUnlessRequested() {
        server = MuServerBuilder.httpsServer().start();
        assertThat(server.httpUri(), is(nullValue()));
    }

    @After public void stopIt() {
        MuAssert.stopAndCheck(server);
    }
}
