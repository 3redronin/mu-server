package io.muserver.rest;

import io.muserver.MuServer;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.PathSegment;
import okhttp3.Response;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Test;
import scaffolding.MuAssert;
import scaffolding.ServerUtils;

import java.io.IOException;
import java.net.URI;
import java.util.List;

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
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), is("TIGER"));
        }
        try (Response resp = call(request(server.uri().resolve("/dog/tiger/tiger/tiger")))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), is("tiger"));
        }
        try (Response resp = call(request(server.uri().resolve("/tiger/tiger/tiger/dog")))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), is("dog"));
        }
    }

    @Test
    public void repeatedPathParamsAreInjectedAsAList() throws IOException {
        @Path("/repeated/{id}/{id}/{id}")
        class RepeatedPathResource {
            @GET
            public String get(@PathParam("id") List<String> ids) {
                return String.join(",", ids);
            }
        }
        server = ServerUtils.httpsServerForTest()
            .addHandler(restHandler(new RepeatedPathResource()).build())
            .start();
        try (Response resp = call(request(server.uri().resolve("/repeated/one/two/three")))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), is("one,two,three"));
        }
    }

    @Test
    public void repeatedPathSegmentsRetainMatrixParameters() throws IOException {
        @Path("/segments/{id}/{id}")
        class RepeatedPathResource {
            @GET
            public String get(@PathParam("id") List<PathSegment> ids) {
                return ids.get(0).getPath() + ":" + ids.get(0).getMatrixParameters().getFirst("color") + ","
                    + ids.get(1).getPath() + ":" + ids.get(1).getMatrixParameters().getFirst("color");
            }
        }
        server = ServerUtils.httpsServerForTest()
            .addHandler(restHandler(new RepeatedPathResource()).build())
            .start();
        try (Response resp = call(request(server.uri().resolve("/segments/a;color=red/b;color=blue")))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), is("a:red,b:blue"));
        }
    }

    @Test
    public void absentPathSegmentCollectionsUseDefaultValues() throws IOException {
        @Path("/default-segments")
        class DefaultPathResource {
            @GET
            public String get(@DefaultValue("DEFAULT;color=red") @PathParam("missing") List<PathSegment> segments) {
                PathSegment segment = segments.get(0);
                return segment.getPath() + ":" + segment.getMatrixParameters().getFirst("color");
            }
        }
        server = ServerUtils.httpsServerForTest()
            .addHandler(restHandler(new DefaultPathResource()).build())
            .start();
        try (Response resp = call(request(server.uri().resolve("/default-segments")))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), is("DEFAULT:red"));
        }
    }

    @Test
    public void repeatedCapturesWinPathMatchingTies() throws IOException {
        @Path("/rank")
        class RankedResource {
            @GET
            @Path("x/{id}")
            public String oneCapture() {
                return "one capture";
            }

            @GET
            @Path("x/{id}{id}")
            public String twoCaptures() {
                return "two captures";
            }
        }
        server = ServerUtils.httpsServerForTest()
            .addHandler(restHandler(new RankedResource()).build())
            .start();
        try (Response resp = call(request(server.uri().resolve("/rank/x/aa")))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), is("two captures"));
        }
    }

    @Test
    public void repeatedNonDefaultCapturesWinPathMatchingTies() throws IOException {
        @Path("/regex-rank")
        class RankedResource {
            @GET
            @Path("x/{id:\\d+}{id}")
            public String oneRegex() {
                return "one regex";
            }

            @GET
            @Path("x/{id:\\d+}{id:\\d+}")
            public String twoRegexes() {
                return "two regexes";
            }
        }
        server = ServerUtils.httpsServerForTest()
            .addHandler(restHandler(new RankedResource()).build())
            .start();
        try (Response resp = call(request(server.uri().resolve("/regex-rank/x/12")))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), is("two regexes"));
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
    public void absentPathParamsUseDefaultValues() throws Exception {
        @Path("/resource")
        class Resource {
            @GET
            @Path("sbpath/{param}")
            public String supplied(@PathParam("param") String param) {
                return param;
            }

            @GET
            @Path("sbpath/default")
            public String defaulted(@DefaultValue("DEFAULT") @PathParam("param") String param) {
                return param;
            }
        }
        @Path("/locator")
        class Locator {
            @Path("sbpath")
            public Resource locate() {
                return new Resource();
            }
        }

        this.server = httpsServerForTest()
            .addHandler(restHandler(new Resource(), new Locator()).build())
            .start();
        try (Response resp = call(request(server.uri().resolve("/resource/sbpath/default")))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), is("DEFAULT"));
        }
        try (Response resp = call(request(server.uri().resolve("/locator/sbpath/sbpath/default")))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), is("DEFAULT"));
        }
    }

    @Test
    public void emptyPathParamCaptureDoesNotUseDefaultValue() throws Exception {
        @Path("/{param:.*}")
        class Resource {
            @GET
            public String get(@DefaultValue("DEFAULT") @PathParam("param") String param) {
                return param;
            }
        }

        this.server = httpsServerForTest()
            .addHandler(restHandler(new Resource()).build())
            .start();
        try (Response resp = call(request(server.uri()))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), is(""));
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



    @After
    public void stopIt() {
        MuAssert.stopAndCheck(server);
    }


}
