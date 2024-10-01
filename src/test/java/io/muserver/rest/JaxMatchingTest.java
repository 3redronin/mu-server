package io.muserver.rest;

import io.muserver.MuServer;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import okhttp3.Response;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import scaffolding.MuAssert;
import scaffolding.ServerUtils;

import java.io.IOException;
import java.net.URI;

import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;
import static scaffolding.ServerUtils.httpsServerForTest;

public class JaxMatchingTest {
    private MuServer server;

    @Test
    public void canAccessClassPathParamsInMethod() throws IOException {
        @Path("/{thing : [a-z]+}")
        class Thing {
            @GET
            public String get(@PathParam("thing") String thing) {
                return thing;
            }
        }
        server = ServerUtils.httpsServerForTest()
            .addHandler(restHandler(new Thing()).build())
            .start();
        try (Response resp = call(request().url(server.uri().resolve("/tiger").toString()))) {
            assertThat(resp.body().string(), is("tiger"));
        }
        try (Response resp = call(request().url(server.uri().resolve("/TIGER").toString()))) {
            assertThat(resp.code(), is(404));
        }
    }

    @Test
    public void pathsCanRepeatParameters() throws IOException {
        @Path("/{thing : [a-z]+}/{thing}")
        class Thing {
            @GET
            @Path("/{thing}/{thing}")
            public String get(@PathParam("thing") String thing) {
                return thing;
            }
        }
        server = ServerUtils.httpsServerForTest()
            .addHandler(restHandler(new Thing()).build())
            .start();
        try (Response resp = call(request(server.uri().resolve("/tiger/tiger/tiger/tiger")))) {
            assertThat(resp.body().string(), is("tiger")); // uppercut
        }
        try (Response resp = call(request(server.uri().resolve("/TIGER/TIGER/TIGER/TIGER")))) {
            assertThat(resp.code(), is(404));
        }
        try (Response resp = call(request(server.uri().resolve("/tiger/TIGER/TIGER/TIGER")))) {
            assertThat(resp.code(), is(404));
        }
        try (Response resp = call(request(server.uri().resolve("/dog/tiger/tiger/tiger")))) {
            assertThat(resp.code(), is(404));
        }
        try (Response resp = call(request(server.uri().resolve("/tiger/tiger/tiger/dog")))) {
            assertThat(resp.code(), is(404));
        }
    }

    @Test
    public void differentMethodsCanHaveDifferentRegexes() throws IOException {
        @Path("/api")
        class Thing {
            @GET
            @Path("/{id : \\d+}")
            public String get(@PathParam("id") int id) {
                return "got " + id;
            }
            @DELETE
            @Path("{id}")
            public String delete(@PathParam("id") String id) {
                return "deleted " + id;
            }
        }
        server = ServerUtils.httpsServerForTest()
            .addHandler(restHandler(new Thing()).build())
            .start();
        try (Response resp = call(request(server.uri().resolve("/api/123")))) {
            assertThat(resp.body().string(), is("got 123"));
        }
        try (Response resp = call(request(server.uri().resolve("/api/hello")))) {
            assertThat(resp.code(), is(405)); // because DELETE is matched
        }

        try (Response resp = call(request(server.uri().resolve("/api/hmmm")).delete())) {
            assertThat(resp.body().string(), is("deleted hmmm"));
        }
        try (Response resp = call(request(server.uri().resolve("/api/123")).delete())) {
            // TODO is this actually expected?
            assertThat(resp.code(), is(405)); // because it matches the more specific integer regex which only has GET
        }
    }

    @Test
    public void partialMatchesAreNotIncluded() {
        @Path("/runners")
        class Runners {
            @GET
            @Path("/{id}")
            public void id() {}
        }
        server = ServerUtils.httpsServerForTest()
            .addHandler(restHandler(new Runners()))
            .start();
        try (Response resp = call(request(server.uri().resolve("/runners/myrunner/system")))) {
            assertThat(resp.code(), is(404));
        }
    }


    @Test
    public void matrixParametersMatchDefaultPathParamRegex() throws IOException {
        @Path("/blah/{thing}")
        class Thing {
            @GET
            @Path("/something")
            public String get(@PathParam("thing") String thing) {
                return thing;
            }
        }
        server = ServerUtils.httpsServerForTest()
            .addHandler(restHandler(new Thing()).build())
            .start();
        URI matrixUri = server.uri().resolve("/blah;ignored=true/tiger;color=red;type=cat/something;ignored=true");
        try (Response resp = call(request().url(matrixUri.toString()))) {
            assertThat(resp.body().string(), is("tiger"));
        }
    }

    @Test
    public void pathParamsUseTheFinalPathParam() throws Exception {
        @Path("/customers/{id}")
        class CustomerResource {
            @GET
            @Path("/address/{id}")
            public String getAddress(@PathParam("id") String addressId) {return addressId;}
        }
        this.server = httpsServerForTest().addHandler(restHandler(new CustomerResource()).build()).start();
        try (Response resp = call(request(server.uri().resolve("/customers/123/address/456")))) {
            assertThat(resp.code(), Matchers.is(200));
            assertThat(resp.body().string(), containsString("456"));
        }
    }


    @Test
    public void interfacesWithDefaultImplementationSupported() throws IOException {
        server = ServerUtils.httpsServerForTest()
            .addHandler(restHandler(new DefaultImplementationResource()))
            .start();
        try (Response resp = call(request(server.uri().resolve("/default-impl/jax1")))) {
            assertThat(resp.code(), Matchers.is(200));
            assertThat(resp.body().string(), equalTo("Hello from default impl"));
        }
        try (Response resp = call(request(server.uri().resolve("/default-impl/jax2")))) {
            assertThat(resp.code(), Matchers.is(200));
            assertThat(resp.body().string(), equalTo("Overwritten by implementation"));
        }
    }

    class DefaultImplementationResource implements InterfaceWithDefaultImplementation {
        @Override
        public void notAJaxMethod2() {
        }

        @Override
        public String jaxMethodOverwritten() {
            return "Overwritten by implementation";
        }
    }

    @Path("/default-impl")
    interface InterfaceWithDefaultImplementation {
        default void notAJaxMethod() {}
        default void notAJaxMethod2() {}
        @GET
        @Path("jax1")
        default String jaxMethod() { return "Hello from default impl";}
        @GET
        @Path("jax2")
        default String jaxMethodOverwritten() { return "Will be overwritten";}
    }



    @AfterEach
    public void stopIt() {
        MuAssert.stopAndCheck(server);
    }


}