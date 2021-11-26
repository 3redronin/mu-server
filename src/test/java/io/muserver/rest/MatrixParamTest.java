package io.muserver.rest;

import io.muserver.MuServer;
import okhttp3.Response;
import org.junit.After;
import org.junit.Test;
import scaffolding.MuAssert;
import scaffolding.ServerUtils;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.core.PathSegment;
import java.io.IOException;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class MatrixParamTest {
    private MuServer server;

    @Test
    public void canAccessMatrixParamsViaPathSegments() throws IOException {
        @Path("/cars/{make}")
        class CarResource {

            @GET
            @Path("/{model}/{year}")
            public String getPicture(@PathParam("make") String make,
                                   @PathParam("model") PathSegment car,
                                   @PathParam("year") String year) {
                return make + " " + car.getPath() + " with color " + car.getMatrixParameters().getFirst("color") + " from year " + year;
            }
        }

        server = ServerUtils.httpsServerForTest()
            .addHandler(RestHandlerBuilder.restHandler(new CarResource()).build())
            .start();
        try (Response resp = call(request(server.uri().resolve("/cars/mercedes/e55;color=black/2006")))) {
            assertThat(resp.body().string(), is("mercedes e55 with color black from year 2006"));
        }
    }

    @After
    public void stopIt() {
        MuAssert.stopAndCheck(server);
    }

}