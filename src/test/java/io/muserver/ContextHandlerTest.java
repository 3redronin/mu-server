package io.muserver;

import io.muserver.rest.RestHandlerBuilder;
import okhttp3.Response;
import org.junit.After;
import org.junit.Test;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import java.io.IOException;

import static io.muserver.MuServerBuilder.httpsServer;
import static java.util.Arrays.asList;
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
            .addHandler(new ContextHandler("some context",
                    asList(
                        Routes.route(Method.GET, "/bl ah", (request, response, pathParams) -> {
                            response.write("context=" + request.contextPath() + ";relative=" + request.relativePath());
                        }),
                        RestHandlerBuilder.create(new Fruit())
                    )
                )
            ).start();

        try (Response resp = call(request().url(server.uri().resolve("/some%20context/bl%20ah").toString()))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), equalTo("context=/some%20context;relative=/bl%20ah"));
        }
        try (Response resp = call(request().url(server.uri().resolve("/some%20context/fruits").toString()))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), equalTo("Fruity"));
        }
    }

    @After
    public void destroy() {
        if (server != null) server.stop();
    }

}