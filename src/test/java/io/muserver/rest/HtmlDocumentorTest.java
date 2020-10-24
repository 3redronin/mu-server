package io.muserver.rest;

import io.muserver.MuServer;
import io.muserver.Mutils;
import org.junit.After;
import org.junit.Test;
import scaffolding.MuAssert;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;

import static io.muserver.ContextHandlerBuilder.context;
import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;
import static scaffolding.ServerUtils.httpsServerForTest;

public class HtmlDocumentorTest {
    private MuServer server;

    @Test
    public void recursiveSubResourcesWork() throws Exception {
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

            @Path("recursive/{anotherId}")
            public WidgetResource recurse(@PathParam("anotherId") String id) {
                return new WidgetResource(id);
            }
        }

        @Path("widgets")
        @Produces("text/strange")
        class WidgetsResource {
            @Path("{id}")
            public WidgetResource findWidget(@PathParam("id") String id) {
                return new WidgetResource(id);
            }
        }

        server = httpsServerForTest()
            .addHandler(context("/context")
                .addHandler(restHandler(new WidgetsResource()).withOpenApiHtmlUrl("/api.html"))
            )
            .start();

        try (okhttp3.Response resp = call(request(server.uri().resolve("/context/api.html")))) {
            String html = resp.body().string();
            assertThat(resp.code(), is(200));
            assertThat(html, endsWith("</html>"));
            assertThat(html, containsString(Mutils.htmlEncode("/widgets/{id}/category/{cat}")));
            assertThat(html, containsString(Mutils.htmlEncode("/widgets/{id}/recursive/{anotherId}/category/{cat}")));
            assertThat(html, containsString(Mutils.htmlEncode("/widgets/{id}/recursive/{anotherId}/recursive/{anotherId}/category/{cat}")));
            assertThat(html, containsString(Mutils.htmlEncode("/widgets/{id}/recursive/{anotherId}/recursive/{anotherId}/recursive/{anotherId}/category/{cat}")));
        }
    }

    @After
    public void cleanup() {
        MuAssert.stopAndCheck(server);
    }


}