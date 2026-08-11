package io.muserver.rest;

import io.muserver.MuServer;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.PathSegment;
import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import scaffolding.MuAssert;
import scaffolding.ServerUtils;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

import static java.util.Arrays.asList;
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

    @Test
    public void matrixParamsOnResourceMethodAreSegmentLocalAndSupportConversionDefaultsAndPathSegmentAccess() throws IOException {
        @Path("/items/{id}")
        class ItemResource {
            @GET
            public String get(@PathParam("id") String id,
                              @PathParam("id") PathSegment segment,
                              @MatrixParam("idType") String idType,
                              @MatrixParam("missing") @DefaultValue("17") int missing,
                              @MatrixParam("tag") List<String> tags) {
                return id + "|" + segment.getPath() + "|" + segment.getMatrixParameters().getFirst("idType") + "|" + idType + "|" + missing + "|" + String.join(",", tags);
            }
        }

        server = ServerUtils.httpsServerForTest()
            .addHandler(RestHandlerBuilder.restHandler(new ItemResource()).build())
            .start();
        try (Response resp = call(request(server.uri().resolve("/items/123;idType=legacy;tag=one;tag=two")))) {
            assertThat(resp.body().string(), is("123|123|legacy|legacy|17|one,two"));
        }
        try (Response resp = call(request(server.uri().resolve("/items/123;missing=nope")))) {
            assertThat(resp.code(), is(400));
        }
    }

    @Test
    public void subResourceLocatorReceivesMatrixParamsFromItsOwnMatchedSegment() throws IOException {
        class ChildResource {
            private final String result;
            ChildResource(String result) { this.result = result; }
            @GET
            @Path("children")
            public String child() { return result + "|child"; }
        }
        @Path("/resources")
        class RootResource {
            @Path("/{id}")
            public ChildResource locate(@PathParam("id") String id, @MatrixParam("idType") String idType) {
                return new ChildResource(id + "|" + idType);
            }
        }

        server = ServerUtils.httpsServerForTest()
            .addHandler(RestHandlerBuilder.restHandler(new RootResource()).build())
            .start();
        try (Response resp = call(request(server.uri().resolve("/resources/123;idType=legacy/children")))) {
            assertThat(resp.body().string(), is("123|legacy|child"));
        }
    }

    @Test
    public void sameMatrixParamNameOnMultipleSegmentsResolvesAgainstCurrentMatchedSegment() throws IOException {
        class ChildResource {
            private final String outer;
            ChildResource(String outer) { this.outer = outer; }
            @GET
            @Path("children/ordinary/{childId}")
            public String child(@PathParam("childId") String childId, @MatrixParam("idType") String inner) {
                return outer + "|" + childId + ":" + inner;
            }
        }
        @Path("/resources")
        class RootResource {
            @Path("/{id}")
            public ChildResource locate(@PathParam("id") String id, @MatrixParam("idType") String idType) {
                return new ChildResource(id + ":" + idType);
            }
        }

        server = ServerUtils.httpsServerForTest()
            .addHandler(RestHandlerBuilder.restHandler(new RootResource()).build())
            .start();
        for (String[] uriAndExpected : asList(
            new String[]{"/resources/123;idType=legacy/children/ordinary/456;idType=external", "123:legacy|456:external"},
            new String[]{"/resources/123;idType=legacy/children/ordinary/456", "123:legacy|456:null"},
            new String[]{"/resources/123/children/ordinary/456;idType=external", "123:null|456:external"})) {
            try (Response resp = call(request(server.uri().resolve(uriAndExpected[0])))) {
                assertThat(resp.body().string(), is(uriAndExpected[1]));
            }
        }
    }

    @Test
    public void encodedAndEdgeCaseMatrixValuesUsePathSegmentSemantics() throws IOException {
        @Path("/edge/{id}")
        class EdgeResource {
            @GET
            public String get(@PathParam("id") String id,
                              @PathParam("id") PathSegment segment,
                              @MatrixParam("idType") String idType,
                              @MatrixParam("empty") String empty,
                              @MatrixParam("IDTYPE") String upper) {
                return id + "|" + segment.getPath() + "|" + idType + "|" + segment.getMatrixParameters().getFirst("kind") + "|" + empty + "|" + upper;
            }
        }

        server = ServerUtils.httpsServerForTest()
            .addHandler(RestHandlerBuilder.restHandler(new EdgeResource()).build())
            .start();
        try (Response resp = call(request(server.uri().resolve("/edge/a%3Bb;idType=leg%20acy;kind=primary;empty;IDTYPE=upper?x=1")))) {
            assertThat(resp.body().string(), is("a;b|a;b|leg acy|primary||upper"));
        }
    }

    @AfterEach
    public void stopIt() {
        MuAssert.stopAndCheck(server);
    }

}
