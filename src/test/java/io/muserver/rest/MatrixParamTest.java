package io.muserver.rest;

import io.muserver.MuServer;
import okhttp3.Response;
import org.junit.After;
import org.junit.Test;
import scaffolding.MuAssert;
import scaffolding.ServerUtils;

import javax.ws.rs.*;
import javax.ws.rs.core.PathSegment;
import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

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
            public String getPicture(@PathParam("make") PathSegment make,
                                   @PathParam("model") @Encoded PathSegment car,
                                   @PathParam("year") String year) {
                return make.getPath() + "-" + make.getMatrixParameters().getFirst("surname") + " " + car.getPath() + " with color " + car.getMatrixParameters().getFirst("color") + " from year " + year;
            }
        }

        server = ServerUtils.httpsServerForTest()
            .addHandler(RestHandlerBuilder.restHandler(new CarResource()).build())
            .start();
        try (Response resp = call(request(server.uri().resolve("/cars/mercedes;surname=be%20nz/e55;color=black%20blue/2006")))) {
            assertThat(resp.body().string(), is("mercedes-be nz e55 with color black%20blue from year 2006"));
        }
    }

    @Test
    public void matrixParamCanGetMatrixParamsFromLastSegment() throws IOException {
        @Path("/cars")
        class CarResource {
            @GET
            @Path("/{make}/{model}")
            public String getPicture(
                @MatrixParam("country") String country,
                @MatrixParam("colour") List<String> colours,
                                     @MatrixParam("year") int year
            ) {
                return year + ": " + colours.stream().sorted().collect(Collectors.joining(", "));
            }
        }

        server = ServerUtils.httpsServerForTest()
            .addHandler(RestHandlerBuilder.restHandler(new CarResource()).build())
            .start();
        try (Response resp = call(request(server.uri().resolve("/cars/mercedes;country=Germany/e55;colour=black;colour=blue;year=2021")))) {
            assertThat(resp.body().string(), is("2021: black, blue"));
        }
    }

    @After
    public void stopIt() {
        MuAssert.stopAndCheck(server);
    }

}