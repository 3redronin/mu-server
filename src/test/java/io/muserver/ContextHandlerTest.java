package io.muserver;

import io.muserver.rest.RestHandlerBuilder;
import okhttp3.Response;
import org.junit.After;
import org.junit.Test;
import scaffolding.ServerUtils;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import java.io.IOException;
import java.net.URL;

import static io.muserver.ContextHandlerBuilder.context;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
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

        server = ServerUtils.httpsServerForTest()
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

        server = ServerUtils.httpsServerForTest()
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
        server = ServerUtils.httpsServerForTest()
            .addHandler(context("my app"))
            .start();

        URL url = server.uri().resolve("/my%20app").toURL();
        try (Response resp = call(request().get().url(url))) {
            assertThat(resp.code(), equalTo(302));
            assertThat(resp.header("location"), equalTo(server.uri().resolve("/my%20app/").toString()));
            assertThat(resp.body().contentLength(), equalTo(-1L));
        }
    }

    @Test
    public void contextIsEmptyStringIfNotUsed() throws IOException {

        server = ServerUtils.httpsServerForTest()
            .addHandler(
                Routes.route(Method.GET, "/", (request, response, pathParams) -> {
                    response.write("context=" + request.contextPath() + ";relative=" + request.relativePath());
                }))
            .start();

        try (Response resp = call(request(server.uri().resolve("/")))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), equalTo("context=;relative=/"));
        }
        try (Response resp = call(request(server.uri().resolve("")))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), equalTo("context=;relative=/"));
        }
    }

    @Test
    public void ifContextIsEmptyThenItJustPassesToChildHandlers() throws IOException {
        String[] empties = {"", "/", " ", " / ", "//", null};
        for (String empty : empties) {
            server = ServerUtils.httpsServerForTest()
                .addHandler(
                    context(empty)
                        .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                            response.write("I got it. " + request.contextPath().isEmpty() + " and "
                                + request.relativePath());
                        }))
                .start();
            try (Response resp = call(request(server.uri().resolve("/")))) {
                assertThat(resp.code(), is(200));
                assertThat(resp.body().string(), equalTo("I got it. true and /"));
            }
        }
    }

    @Test
    public void contextsOnlyApplyToHandlersAddedToThem() throws IOException {
        server = ServerUtils.httpsServerForTest()
            .addHandler(context("a"))
            .addHandler(Method.GET, "/b", (request, response, pathParams) -> {
                response.write(request.contextPath() + " - " + request.relativePath());
            })
            .start();
        try (Response resp = call(request(server.uri().resolve("/b")))) {
            assertThat(resp.body().string(), is(" - /b"));
        }
        try (Response resp = call(request(server.uri().resolve("/a/b")))) {
            assertThat(resp.code(), is(404));
        }
    }

    @Test
    public void unreservedCharactersComeThroughUnencoded() throws Exception {
        server = ServerUtils.httpsServerForTest()
            .addHandler(context("~.-_")
                .addHandler(Method.GET, "~.-_", (request, response, pathParams) -> {
                    response.write(request.contextPath() + " - " + request.relativePath());
                })
            )
            .start();
        try (Response resp = call(request(server.uri().resolve("/~.-_/~.-_")))) {
            assertThat(resp.body().string(), is("/~.-_ - /~.-_"));
            assertThat(resp.code(), is(200));
        }
        try (Response resp = call(request(server.uri().resolve("/%7E%2E%2D%5F/%7E%2E%2D%5F")))) {
            assertThat(resp.body().string(), is("/~.-_ - /~.-_"));
            assertThat(resp.code(), is(200));
        }
    }

    @After
    public void destroy() {
        scaffolding.MuAssert.stopAndCheck(server);
    }

}