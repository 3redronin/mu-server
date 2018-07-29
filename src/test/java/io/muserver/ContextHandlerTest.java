package io.muserver;

import io.muserver.rest.RestHandlerBuilder;
import okhttp3.Response;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Test;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import java.io.IOException;
import java.net.URL;

import static io.muserver.ContextHandlerBuilder.context;
import static io.muserver.MuServerBuilder.httpsServer;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class ContextHandlerTest {

    private MuServer server;

    @Test
    public void canAddRoutesAndResources() throws IOException {

        @Path("/fruits")
        class Fruit {
            @GET
            public String get() {
                return "Fruity";
            }
        }

        server = httpsServer()
            .addHandler(
                context("some context")
                    .addHandler(
                        Routes.route(Method.GET, "/bl ah", (request, response, pathParams) -> {
                            response.write("context=" + request.contextPath() + ";relative=" + request.relativePath());
                        }))
                    .addHandler(
                        context("phase two")
                            .addHandler((request, response) -> {
                                response.headers().add("X-Blah", "added in context. " + "context=" + request.contextPath() + ";relative=" + request.relativePath());
                                return false;
                            })
                            .addHandler(RestHandlerBuilder.restHandler(new Fruit())))
            ).start();

        try (Response resp = call(request().url(server.uri().resolve("/some%20context/bl%20ah").toString()))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), equalTo("context=/some%20context;relative=/bl%20ah"));
        }
        try (Response resp = call(request().url(server.uri().resolve("/some%20context/phase%20two/fruits").toString()))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), equalTo("Fruity"));
            assertThat(resp.header("X-Blah"), equalTo("added in context. context=/some%20context/phase%20two;relative=/fruits"));
        }
    }

    @Test
    public void slashesAreAllowable() throws IOException {

        @Path("/fruits")
        class Fruit {
            @GET
            public String get() {
                return "Fruity";
            }
        }

        server = httpsServer()
            .addHandler(
                context("/some context/")
                    .addHandler(
                        Routes.route(Method.GET, "/bl ah", (request, response, pathParams) -> {
                            response.write("context=" + request.contextPath() + ";relative=" + request.relativePath());
                        }))
                    .addHandler(
                        context("/phase two/")
                            .addHandler((request, response) -> {
                                response.headers().add("X-Blah", "added in context. " + "context=" + request.contextPath() + ";relative=" + request.relativePath());
                                return false;
                            })
                            .addHandler(RestHandlerBuilder.restHandler(new Fruit())))
            ).start();

        try (Response resp = call(request().url(server.uri().resolve("/some%20context/bl%20ah").toString()))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), equalTo("context=/some%20context;relative=/bl%20ah"));
        }
        try (Response resp = call(request().url(server.uri().resolve("/some%20context/phase%20two/fruits").toString()))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), equalTo("Fruity"));
            assertThat(resp.header("X-Blah"), equalTo("added in context. context=/some%20context/phase%20two;relative=/fruits"));
        }
    }

    @Test
    public void callsToContextNamesWithoutTrailingSlashesResultIn302() throws Exception {
        server = MuServerBuilder.httpsServer()
            .addHandler(context("my-app"))
            .start();

        URL url = server.uri().resolve("/my-app").toURL();
        try (Response resp = call(request().get().url(url))) {
            assertThat(resp.code(), Matchers.equalTo(302));
            assertThat(resp.header("location"), Matchers.equalTo(server.uri().resolve("/my-app/").toString()));
            assertThat(resp.body().contentLength(), Matchers.equalTo(0L));
        }
    }

    @Test
    public void contextIsEmptyStringIfNotUsed() throws IOException {

        server = httpsServer()
            .addHandler(
                Routes.route(Method.GET, "/", (request, response, pathParams) -> {
                    response.write("context=" + request.contextPath() + ";relative=" + request.relativePath());
                }))
            .start();

        try (Response resp = call(request().url(server.uri().resolve("/").toString()))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), equalTo("context=;relative=/"));
        }
        try (Response resp = call(request().url(server.uri().resolve("").toString()))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), equalTo("context=;relative=/"));
        }
    }

    @After
    public void destroy() {
        scaffolding.MuAssert.stopAndCheck(server);
    }

}