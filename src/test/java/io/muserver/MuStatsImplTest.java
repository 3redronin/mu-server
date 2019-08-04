package io.muserver;

import okhttp3.Response;
import org.junit.After;
import org.junit.Test;
import scaffolding.ServerUtils;

import java.io.IOException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class MuStatsImplTest {
    private MuServer server;

    @Test
    public void statsReflectsRequests() throws IOException {

        server = ServerUtils.httpsServerForTest()
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                MuStats stats = request.server().stats();
                response.write(stats.toString());
            })
            .start();

        try (Response resp = call(request(server.uri()))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), startsWith("Active requests: 1; completed requests: 0; active connections: 1; completed connections: 0; invalid requests: 0;"));
        }
        try (Response resp = call(request(server.uri()))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), startsWith("Active requests: 1; completed requests: 1; active connections: 1; completed connections: 0; invalid requests: 0;"));
        }

    }


    @After
    public void destroy() {
        scaffolding.MuAssert.stopAndCheck(server);
    }

}