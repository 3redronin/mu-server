package io.muserver.rest;

import io.muserver.MuServer;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import org.junit.After;
import org.junit.Test;
import scaffolding.MuAssert;
import scaffolding.ServerUtils;

import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class RestHandlerTest {
    private MuServer server = ServerUtils.httpsServerForTest()
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

    @Test
    public void voidMethodsReturn204() {
        try (okhttp3.Response resp = call(request(server.uri().resolve("/api/fruit%20bits/nothing")))) {
            assertThat(resp.code(), is(204));
            assertThat(resp.header("Content-Length"), is(nullValue()));
            assertThat(resp.body().contentLength(), lessThanOrEqualTo(0L));
        }
    }

    @Test
    public void nullValuesReturn204() {
        try (okhttp3.Response resp = call(request(server.uri().resolve("/api/fruit%20bits/nothing2")))) {
            assertThat(resp.code(), is(204));
            assertThat(resp.header("Content-Length"), is(nullValue()));
            assertThat(resp.body().contentLength(), lessThanOrEqualTo(0L));
        }
    }

    @Test
    public void canDefineOwnMethodTypes() throws IOException {
        try (okhttp3.Response resp = call(request(server.uri().resolve("/api/fruit%20bits/custom-get")))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), is("got"));
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

        @GET
        @Path("/nothing")
        public void nothing() {}

        @GET
        @Path("/nothing2")
        public String nothing2() {
            return null;
        }

        @CustomGET
        @Path("custom-get")
        public String hi() {
            return "got";
        }

    }

    @After
    public void stopIt() {
        MuAssert.stopAndCheck(server);
    }

    @Target({ElementType.METHOD})
    @Retention(RetentionPolicy.RUNTIME)
    @HttpMethod(HttpMethod.GET)
    @interface CustomGET {
    }

}
