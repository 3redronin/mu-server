package io.muserver.rest;

import io.muserver.MuServer;
import okhttp3.internal.Util;
import org.junit.After;
import org.junit.Test;

import javax.ws.rs.*;
import javax.ws.rs.core.Response;
import java.io.IOException;

import static io.muserver.MuServerBuilder.httpsServer;
import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;
import static scaffolding.MuAssert.stopAndCheck;

public class OptionsTest {

    private MuServer server;

    @Test
    public void ifAMethodIsAnnotationWithOptionsThenUseIt() {
        @Path("/things")
        class Thing {

            @GET
            public String get() {
                return "Hello!";
            }

            @OPTIONS
            public Response getOptions() {
                return Response.status(400).type("application/json").build();
            }
        }
        server = httpsServer().addHandler(restHandler(new Thing())).start();
        try (okhttp3.Response resp = call(request()
            .method("OPTIONS", Util.EMPTY_REQUEST)
            .url(server.uri().resolve("/things").toString()))) {
            assertThat(resp.code(), is(400));
            assertThat(resp.header("Content-Type"), is("application/json"));
        }

    }

    @Test
    public void anOptionsRequestToTheClassReturnsTheMethodsAvailable() throws IOException {
        @Path("/things")
        class Thing {
            @GET
            public void get() {
            }

            @POST
            public void post() {
            }

            @DELETE
            @Path("/non-root")
            public void delete() {
            }
        }
        server = httpsServer().addHandler(restHandler(new Thing())).start();
        try (okhttp3.Response resp = call(request()
            .method("OPTIONS", Util.EMPTY_REQUEST)
            .url(server.uri().resolve("/things").toString()))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.header("Allow"), is("GET, HEAD, OPTIONS, POST"));
            assertThat(resp.body().contentLength(), is(0L));
        }
        try (okhttp3.Response resp = call(request()
            .method("OPTIONS", Util.EMPTY_REQUEST)
            .url(server.uri().resolve("/things/non-root").toString()))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.header("Allow"), is("DELETE, OPTIONS"));
            assertThat(resp.body().contentLength(), is(0L));
        }
    }

    @Test
    public void a404IsReturnedIfNoOptionsAndNoGet() {
        @Path("/things")
        class Thing {
        }
        server = httpsServer().addHandler(restHandler(new Thing())).start();
        try (okhttp3.Response resp = call(request()
            .method("OPTIONS", Util.EMPTY_REQUEST)
            .url(server.uri().resolve("/things/nothing").toString()))) {
            assertThat(resp.code(), is(404));
        }
    }


    @After
    public void stop() {
        stopAndCheck(server);
    }

}
