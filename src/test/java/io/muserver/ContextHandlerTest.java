package io.muserver;

import io.muserver.rest.RestHandlerBuilder;
import okhttp3.Response;
import org.junit.After;
import org.junit.Test;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import java.io.IOException;

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
            .addHandler(context("some context",
                Routes.route(Method.GET, "/bl ah", (request, response, pathParams) -> {
                    response.write("context=" + request.contextPath() + ";relative=" + request.relativePath());
                }),
                context("phase two",
                    (request, response) -> {
                        response.headers().add("X-Blah", "added in context");
                        return false;
                    },
                    RestHandlerBuilder.restHandler(new Fruit()).build()
                ).build())
            ).start();

        try (Response resp = call(request().url(server.uri().resolve("/some%20context/bl%20ah").toString()))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), equalTo("context=/some%20context;relative=/bl%20ah"));
        }
        try (Response resp = call(request().url(server.uri().resolve("/some%20context/phase%20two/fruits").toString()))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), equalTo("Fruity"));
            assertThat(resp.header("X-Blah"), equalTo("added in context"));
        }
    }

    @After
    public void destroy() {
        if (server != null) server.stop();
    }

}