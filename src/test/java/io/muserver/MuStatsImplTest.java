package io.muserver;

import okhttp3.Response;
import org.junit.After;
import org.junit.Test;

import java.io.IOException;

import static io.muserver.MuServerBuilder.httpsServer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class MuStatsImplTest {
    private MuServer server;

    @Test
    public void statsReflectsRequests() throws IOException {

        server = httpsServer()
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                MuStats stats = request.server().stats();
                response.write(stats.toString());
            })
            .start();

        try (Response resp = call(request(server.uri()))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), startsWith("Completed requests: 0; active: 1; invalid requests: 0;"));
        }
        try (Response resp = call(request(server.uri()))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), startsWith("Completed requests: 1; active: 1; invalid requests: 0;"));
        }

    }


    @After
    public void destroy() {
        scaffolding.MuAssert.stopAndCheck(server);
    }

}