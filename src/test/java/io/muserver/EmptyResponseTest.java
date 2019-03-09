package io.muserver;

import okhttp3.Response;
import org.junit.After;
import org.junit.Test;
import scaffolding.ClientUtils;

import java.io.IOException;

import static io.muserver.MuServerBuilder.httpsServer;
import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static scaffolding.ClientUtils.request;

public class EmptyResponseTest {
    private MuServer server;

    @Test
    public void a304HasNoBody() throws IOException {
        server = httpsServer()
            .addHandler(Method.GET, "/", (request, response, pathParams) -> response.status(304))
            .start();

        try (Response resp = ClientUtils.call(request(server.uri()))) {
            assertThat(resp.code(), is(304));
            assertThat(resp.header("Date"), is(notNullValue()));
            assertThat(resp.header("Content-Length"), is(nullValue()));
            assertThat(resp.body().bytes().length, is(0));
        }
    }

    @After
    public void destroy() {
        scaffolding.MuAssert.stopAndCheck(server);
    }
}