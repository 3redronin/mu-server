package io.muserver.rest;

import io.muserver.MuServer;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;
import static scaffolding.MuAssert.stopAndCheck;
import static scaffolding.ServerUtils.httpsServerForTest;

public class RootResourcePathTest {
    private MuServer server;

    public static class Methods {
        @GET public String root() { return "root"; }
        @GET @Path("children/{id}") public String child(@PathParam("id") String id) { return id; }
    }

    @Path("") public static class EmptyRoot extends Methods { }
    @Path("/") public static class SlashRoot extends Methods { }

    @AfterEach public void stop() { stopAndCheck(server); }

    @Test public void emptyRootPathMatchesMethodPaths() throws Exception { checkRoot(new EmptyRoot()); }
    @Test public void slashRootPathMatchesMethodPaths() throws Exception { checkRoot(new SlashRoot()); }

    private void checkRoot(Object resource) throws Exception {
        server = httpsServerForTest().addHandler(RestHandlerBuilder.restHandler(resource)).start();
        check("/", 200, "root");
        check("/children/a%20b", 200, "a b");
        check("/missing", 404, null);
        check("/children/a/extra", 404, null);
    }

    private void check(String path, int status, String body) throws Exception {
        try (okhttp3.Response response = call(request(server.uri().resolve(path)))) {
            assertThat(response.code(), is(status));
            if (body != null) { assertThat(response.body().string(), is(body)); }
        }
    }
}
