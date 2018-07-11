package io.muserver.rest;

import io.muserver.MuServer;
import org.junit.After;
import org.junit.Test;

import javax.ws.rs.GET;
import javax.ws.rs.HEAD;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.core.Response;
import java.io.IOException;

import static io.muserver.MuServerBuilder.httpsServer;
import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
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
        server = httpsServer().addHandler(restHandler(new Thing())).start();
        try (okhttp3.Response resp = call(request()
            .head()
            .url(server.uri().resolve("/things").toString()))) {
            assertThat(resp.code(), is(400));
            assertThat(resp.header("Content-Type"), is("application/json"));
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
        server = httpsServer().addHandler(restHandler(new Thing())).start();
        try (okhttp3.Response resp = call(request()
            .get()
            .url(server.uri().resolve("/things").toString()))) {
            assertThat(resp.code(), is(400));
            assertThat(resp.header("Content-Type"), is("application/json"));
            assertThat(resp.body().string(), is("I am an entity"));
        }
        try (okhttp3.Response resp = call(request()
            .head()
            .url(server.uri().resolve("/things").toString()))) {
            assertThat(resp.code(), is(400));
            assertThat(resp.header("Content-Type"), is("application/json"));
            assertThat(resp.body().contentLength(), is(0L));
        }
        // Note: transfer type changes from chunked to a specific length
    }

    @Test
    public void a405IsReturnedIfNoHeadAndNoGet() {
        @Path("/things")
        class Thing {
            @POST
            public Response get() {
                return Response.status(400).type("application/json").entity("I am an entity").build();
            }
        }
        server = httpsServer().addHandler(restHandler(new Thing())).start();
        try (okhttp3.Response resp = call(request()
            .head()
            .url(server.uri().resolve("/things").toString()))) {
            assertThat(resp.code(), is(405));
            assertThat(resp.body().contentLength(), is(0L));
        }
    }


    @After
    public void stop() {
        stopAndCheck(server);
    }

}
