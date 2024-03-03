package io.muserver.rest;

import io.muserver.MuServer;
import okhttp3.Response;
import org.junit.After;
import org.junit.Test;
import scaffolding.ServerUtils;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.SecurityContext;
import java.io.IOException;
import java.security.Principal;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
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

        server = ServerUtils.httpsServerForTest().addHandler(RestHandlerBuilder.restHandler(new Blah())).start();
        try (Response resp = call(request().url(server.uri().resolve("/things").toString()))) {
            assertThat(resp.body().string(), is("null / true / false / null"));
        }

    }


    @Test
    public void customContextsCanBeInjected() throws IOException {

        abstract class BaseSecurityContext implements SecurityContext {
        }
        class CustomSecurityContext extends BaseSecurityContext {
            public Principal getUserPrincipal() {
                return () -> "Skinner";
            }

            public boolean isUserInRole(String role) {
                return true;
            }

            public boolean isSecure() {
                return false;
            }

            public String getAuthenticationScheme() {
                return "custom";
            }
        }

        @Path("things")
        class Blah {
            @GET
            public String get(@Context CustomSecurityContext securityContext) {
                if (securityContext == null) return "null context";
                return securityContext.getAuthenticationScheme() + " / " + securityContext.isSecure() + " / "
                    + securityContext.isUserInRole("Admin") + " / " + securityContext.getUserPrincipal().getName();
            }

            @GET
            @Path("/base")
            public String get(@Context BaseSecurityContext securityContext) {
                if (securityContext == null) return "null context";
                return securityContext.getAuthenticationScheme() + " / " + securityContext.isSecure() + " / "
                    + securityContext.isUserInRole("Admin") + " / " + securityContext.getUserPrincipal().getName();
            }

            @GET
            @Path("/interface")
            public String get(@Context SecurityContext securityContext) {
                if (securityContext == null) return "null context";
                return securityContext.getAuthenticationScheme() + " / " + securityContext.isSecure() + " / "
                    + securityContext.isUserInRole("Admin") + " / " + securityContext.getUserPrincipal().getName();
            }

        }

        class DifferentSecurityContext implements SecurityContext {
            public Principal getUserPrincipal() {
                return () -> "Differ";
            }

            public boolean isUserInRole(String role) {
                return false;
            }

            public boolean isSecure() {
                return false;
            }

            public String getAuthenticationScheme() {
                return "diff";
            }
        }

        server = ServerUtils.httpsServerForTest().addHandler(RestHandlerBuilder
            .restHandler(new Blah())
            .addRequestFilter(requestContext -> {
                if ("null".equals(requestContext.getHeaderString("type"))) {
                    requestContext.setSecurityContext(null);
                } else if ("diff".equals(requestContext.getHeaderString("type"))) {
                    requestContext.setSecurityContext(new DifferentSecurityContext());
                } else {
                    requestContext.setSecurityContext(new CustomSecurityContext());
                }
            })
        ).start();
        try (Response resp = call(request(server.uri().resolve("/things")))) {
            assertThat(resp.body().string(), is("custom / false / true / Skinner"));
        }
        try (Response resp = call(request(server.uri().resolve("/things/base")))) {
            assertThat(resp.body().string(), is("custom / false / true / Skinner"));
        }
        try (Response resp = call(request(server.uri().resolve("/things/interface")))) {
            assertThat(resp.body().string(), is("custom / false / true / Skinner"));
        }

        try (Response resp = call(request(server.uri().resolve("/things")).header("type", "null"))) {
            assertThat(resp.body().string(), is("null context"));
        }
        try (Response resp = call(request(server.uri().resolve("/things/base")).header("type", "null"))) {
            assertThat(resp.body().string(), is("null context"));
        }
        try (Response resp = call(request(server.uri().resolve("/things/interface")).header("type", "null"))) {
            assertThat(resp.body().string(), is("null context"));
        }

        try (Response resp = call(request(server.uri().resolve("/things")).header("type", "diff"))) {
            assertThat(resp.code(), equalTo(500));
        }
        try (Response resp = call(request(server.uri().resolve("/things/base")).header("type", "diff"))) {
            assertThat(resp.code(), equalTo(500));
        }
        try (Response resp = call(request(server.uri().resolve("/things/interface")).header("type", "diff"))) {
            assertThat(resp.body().string(), is("diff / false / false / Differ"));
        }
    }

    @After
    public void stop() {
        stopAndCheck(server);
    }
}
