package io.muserver.rest;

import io.muserver.MuServer;
import okhttp3.Response;
import org.junit.After;
import org.junit.Test;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;
import java.util.Collections;
import java.util.List;

import static io.muserver.ContextHandlerBuilder.context;
import static io.muserver.Mutils.urlEncode;
import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;
import static scaffolding.ServerUtils.httpsServerForTest;

public class QueryStringCommaExploderFilterTest {

    private MuServer server;

    @Test
    public void itCanSeparateCommaSeparatedValuesIntoAList() throws Exception {

        @Path("blah")
        class Blah {
            @GET
            public String header(@QueryParam("list") List<String> values) {
                return values.size() + " values: " + String.join(", ", values);
            }
        }

        server = httpsServerForTest()
            .addHandler(
                context("api").addHandler(
                    restHandler(new Blah())
                        .addRequestFilter(new QueryStringCommaExploderFilter(Collections.singletonList("list")))
                )
            ).start();
        try (Response resp = call(request(server.uri().resolve("/api/blah?list=value%20one&list=" + urlEncode("value two,value three") + "&list=value%20four,value%20five&list=")))) {
            assertThat(resp.body().string(), is("5 values: value one, value two, value three, value four, value five"));
        }
        try (Response resp = call(request(server.uri().resolve("/api/blah?list=")))) {
            assertThat(resp.body().string(), is("0 values: "));
        }
        try (Response resp = call(request(server.uri().resolve("/api/blah")))) {
            assertThat(resp.body().string(), is("0 values: "));
        }
    }


    @After
    public void stopIt() {
        scaffolding.MuAssert.stopAndCheck(server);
    }

}
