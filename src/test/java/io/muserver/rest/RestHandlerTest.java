package io.muserver.rest;

import io.muserver.MuServer;
import io.muserver.MuServerBuilder;
import org.junit.After;
import org.junit.Test;
import scaffolding.MuAssert;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import java.io.IOException;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class RestHandlerTest {
    private MuServer server = MuServerBuilder.httpsServer()
        .addHandler(RestHandlerBuilder.restHandler(new Fruit()).build())
        .start();


    @Test
    public void canGetAll() throws IOException {
        try (okhttp3.Response resp = call(request().url(server.uri().resolve("/api/fruit%20bits").toString()))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), is("[ { \"name\": \"apple\" }, { \"name\": \"orange\" } ]"));
        }
    }

    @Test
    public void canGetOne() throws IOException {
        try (okhttp3.Response resp = call(request().url(server.uri().resolve("/api/fruit%20bits/orange").toString()))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), is("{ \"name\": \"orange\" }"));
        }
    }


    @Test(expected = IllegalArgumentException.class)
    public void throwsIfObjectDoesNotHavePathAnnotation() {
        RestHandlerBuilder.restHandler(new Object()).build();
    }

    @Path("api/fruit bits")
    private static class Fruit {

        @GET
        public String getAll() {
            return "[ { \"name\": \"apple\" }, { \"name\": \"orange\" } ]";
        }

        @GET
        @Path("{name}")
        public String get(@PathParam("name") String name) {
            switch (name) {
                case "apple":
                    return "{ \"name\": \"apple\" }";
                case "orange":
                    return "{ \"name\": \"orange\" }";
            }
            return "not found";
        }
    }

    @After
    public void stopIt() {
        MuAssert.stopAndCheck(server);
    }


}