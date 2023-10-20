package io.muserver.rest;

import io.muserver.MuServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import scaffolding.ServerUtils;

import javax.ws.rs.*;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;
import java.io.IOException;

import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;
import static scaffolding.MuAssert.stopAndCheck;

public class HeadTest {

    private MuServer server;

    @Test
    public void ifAMethodIsAnnotationWithHeadThenUseIt() {
        @Path("/things")
        class Thing {

            @GET
            public String get() {
                return "Hello!";
            }

            @HEAD
            public Response getHead() {
                return Response.status(400).type("application/json").build();
            }
        }
        server = ServerUtils.httpsServerForTest().addHandler(restHandler(new Thing())).start();
        try (okhttp3.Response resp = call(request()
            .head()
            .url(server.uri().resolve("/things").toString()))) {
            assertThat(resp.code(), is(400));
            assertThat(resp.header("Content-Type"), is("application/json"));
        }

    }

    @Test
    public void headWorksAgainstGetMethodsWithFixedLength() {
        @Path("/things")
        class Thing {
            @GET
            @Produces("text/custom;charset=utf-8")
            public String get() {
                return "Hello!";
            }
        }
        server = ServerUtils.httpsServerForTest().addHandler(restHandler(new Thing())).start();
        try (okhttp3.Response resp = call(request(server.uri().resolve("/things"))
            .head())) {
            assertThat(resp.code(), is(200));
            assertThat(resp.headers("Content-Type"), contains("text/custom;charset=utf-8"));
            assertThat(resp.headers("Content-Length"), contains("6"));
        }

    }

    @Test
    public void headWorksAgainstGetMethodsWithUnknownLength() {
        @Path("/things")
        class Thing {
            @GET
            @Produces("application/custom")
            public StreamingOutput get() {
                return output -> {};
            }
        }
        server = ServerUtils.httpsServerForTest().addHandler(restHandler(new Thing())).start();
        try (okhttp3.Response resp = call(request(server.uri().resolve("/things"))
            .head())) {
            assertThat(resp.code(), is(200));
            assertThat(resp.headers("Content-Type"), contains("application/custom"));
            assertThat(resp.headers("Content-Length"), empty());
        }

    }



    @Test
    public void ifNoHeadAvailableThenUseGetIfAvailableButDiscardBody() throws IOException {
        @Path("/things")
        class Thing {

            @GET
            public Response get() {
                return Response.status(400).type("application/json").entity("I am an entity").build();
            }
            @POST
            public Response post() {
                return Response.status(409).type("text/plain").entity("I am an entity of the state").build();
            }
        }
        server = ServerUtils.httpsServerForTest().addHandler(restHandler(new Thing())).start();
        try (okhttp3.Response resp = call(request()
            .get()
            .url(server.uri().resolve("/things").toString()))) {
            assertThat(resp.code(), is(400));
            assertThat(resp.header("Content-Type"), is("application/json"));
            assertThat(resp.body().string(), is("I am an entity"));
            assertThat(resp.header("Content-Length"), is("14"));
        }
        try (okhttp3.Response resp = call(request()
            .head()
            .url(server.uri().resolve("/things").toString()))) {
            assertThat(resp.code(), is(400));
            assertThat(resp.header("Content-Type"), is("application/json"));
            assertThat(resp.header("Content-Length"), is("14"));
            assertThat(resp.body().string(), is(""));

        }
        // Note: transfer type changes from chunked to a specific length
    }

    @Test
    public void a405IsReturnedIfNoHeadAndNoGet() throws IOException {
        @Path("/things")
        class Thing {
            @POST
            public Response get() {
                return Response.status(400).type("application/json").entity("I am an entity").build();
            }
        }
        server = ServerUtils.httpsServerForTest().addHandler(restHandler(new Thing())).start();
        try (okhttp3.Response resp = call(request()
            .head()
            .url(server.uri().resolve("/things").toString()))) {
            assertThat(resp.code(), is(405));
            assertThat(resp.body().string(), is(""));
        }
    }


    @AfterEach
    public void stop() {
        stopAndCheck(server);
    }

}
