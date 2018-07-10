package io.muserver.rest;

import io.muserver.MuServer;
import okhttp3.Response;
import org.junit.After;
import org.junit.Test;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.SecurityContext;
import java.io.IOException;

import static io.muserver.MuServerBuilder.httpsServer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;
import static scaffolding.MuAssert.stopAndCheck;

public class SecurityContextTest {


    private MuServer server;

    @Test
    public void aSecurityContextIsAlwaysAvailable() throws IOException {

        @Path("things")
        class Blah {
            @GET
            public String get(@Context SecurityContext securityContext) {
                return securityContext.getAuthenticationScheme() + " / " + securityContext.isSecure() + " / "
                    + securityContext.isUserInRole("Admin") + " / " + securityContext.getUserPrincipal();
            }
        }

        server = httpsServer().addHandler(RestHandlerBuilder.restHandler(new Blah())).start();
        try (Response resp = call(request().url(server.uri().resolve("/things").toString()))) {
            assertThat(resp.body().string(), is("null / true / false / null"));
        }

    }

    @After
    public void stop() {
        stopAndCheck(server);
    }
}
