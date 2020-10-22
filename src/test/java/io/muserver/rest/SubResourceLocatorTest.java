package io.muserver.rest;

import io.muserver.MuServer;
import okhttp3.Response;
import org.junit.After;
import org.junit.Test;
import scaffolding.MuAssert;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;

import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;
import static scaffolding.ServerUtils.httpsServerForTest;

public class SubResourceLocatorTest {

    private MuServer server;

    @Test
    public void resourceMethodsInASubResourceWork() throws Exception {
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
        try (Response resp = call(request(server.uri().resolve("/widgets/offers")))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), is("discounted"));
        }
    }

    @Test
    public void subResourceMethodsInASubResourceWork() throws Exception {
        class WidgetResource {
            private final String id;
            public WidgetResource(String id) {
                this.id = id;
            }

            @GET
            @Path("category/{cat}")
            public String getDetails(@PathParam("cat") String cat) {
                return "Widget " + id + " in cat " + cat;
            }
        }

        @Path("widgets")
        @Produces("text/strange")
        class WidgetsResource {
            @Path("{id}")
            public Object findWidget(@PathParam("id") String id) {
                return new WidgetResource(id);
            }
        }

        server = httpsServerForTest()
            .addHandler(restHandler(new WidgetsResource()))
            .start();

        try (Response resp = call(request(server.uri().resolve("/widgets/xxx/category/sheep")))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.header("content-type"), is("text/strange;charset=utf-8"));
            assertThat(resp.body().string(), is("Widget xxx in cat sheep"));
        }
    }


    @After
    public void stop() {
        MuAssert.stopAndCheck(server);
    }
}
