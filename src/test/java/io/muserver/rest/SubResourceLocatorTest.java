package io.muserver.rest;

import io.muserver.MuServer;
import okhttp3.Response;
import org.junit.After;
import org.junit.Test;
import scaffolding.MuAssert;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;

import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;
import static scaffolding.ServerUtils.httpsServerForTest;

public class SubResourceLocatorTest {

    private MuServer server;

    @Test
    public void blah() throws Exception {
        class WidgetResource {
            private final String id;
            public WidgetResource(String id) {
                this.id = id;
            }
            @GET
            public String getDetails() {
                return "Widget " + id;
            }
        }

        @Path("widgets")
        class WidgetsResource {
            @GET
            @Path("offers")
            public String getDiscounted() {
                return "discounted";
            }
            @Path("{id}")
            public WidgetResource findWidget(@PathParam("id") String id) {
                return new WidgetResource(id);
            }
        }

        server = httpsServerForTest()
            .addHandler(restHandler(new WidgetsResource()))
            .start();

        try (Response resp = call(request(server.uri().resolve("/widgets/xxx")))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), is("Widget xxx"));
        }
    }

    @After
    public void stop() {
        MuAssert.stopAndCheck(server);
    }
}
