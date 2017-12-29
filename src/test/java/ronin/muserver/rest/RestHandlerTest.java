package ronin.muserver.rest;

import org.junit.After;
import org.junit.Test;
import ronin.muserver.Method;
import ronin.muserver.MuServer;
import scaffolding.ClientUtils;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.core.Response;

import java.io.IOException;
import java.net.URI;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static ronin.muserver.MuServerBuilder.httpsServer;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class RestHandlerTest {
    private MuServer server = httpsServer()
        .addHandler(new RestHandler(new Fruit()))
        .start();


    @Test
    public void canGetAll() throws IOException {
        try (okhttp3.Response resp = call(request().url(server.uri().resolve("/api/fruits").toString()))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), is("[ { \"name\": \"apple\" }, { \"name\": \"orange\" } ]"));
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void throwsIfObjectDoesNotHavePathAnnotation() {
        new RestHandler(new Object());
    }

    @Path("/api/fruits")
    private static class Fruit {

        @GET
        public String getAll() {
            return "[ { \"name\": \"apple\" }, { \"name\": \"orange\" } ]";
        }

        @Path("/:name")
        @GET String get(@PathParam("name") String name) {
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
        server.stop();
    }


}