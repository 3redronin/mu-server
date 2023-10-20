package io.muserver.rest;

import io.muserver.MuServer;
import okhttp3.Response;
import org.junit.Assert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import scaffolding.ServerUtils;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;
import java.util.List;

import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class CollectionParameterStrategyTest {

    private MuServer server;

    @Test
    public void ifNoCollectionsInResourcesThenThisIsNotReallyRelevant() throws Exception {
        @Path("values")
        class ValuesResource {
            @GET
            public String getIt(@QueryParam("value") String value) {
                return value;
            }
        }
        server = ServerUtils.httpsServerForTest()
            .addHandler(restHandler(new ValuesResource()))
            .start();
        try (Response resp = call(request(server.uri().resolve("/values?value=one,two,three")))) {
            assertThat(resp.body().string(), equalTo("one,two,three"));
        }
    }

    @Test
    public void collectionsWithNoTransformReturnSingleValues() throws Exception {
        @Path("values")
        class ValuesResource {
            @GET
            public String getIt(@QueryParam("value") List<String> values) {
                return values.size() + " values: " + String.join(", ", values);
            }
        }
        server = ServerUtils.httpsServerForTest()
            .addHandler(restHandler(new ValuesResource())
                .withCollectionParameterStrategy(CollectionParameterStrategy.NO_TRANSFORM)
            )
            .start();
        try (Response resp = call(request(server.uri().resolve("/values")))) {
            assertThat(resp.body().string(), equalTo("0 values: "));
        }
        try (Response resp = call(request(server.uri().resolve("/values?value=")))) {
            assertThat(resp.body().string(), equalTo("1 values: "));
        }
        try (Response resp = call(request(server.uri().resolve("/values?value=one,two,three")))) {
            assertThat(resp.body().string(), equalTo("1 values: one,two,three"));
        }
        try (Response resp = call(request(server.uri().resolve("/values?value=one,two,three&value=four")))) {
            assertThat(resp.body().string(), equalTo("2 values: one,two,three, four"));
        }
    }

    @Test
    public void collectionsWithSplitReturnMultipleValues() throws Exception {
        @Path("values")
        class ValuesResource {
            @GET
            public String getIt(@QueryParam("value") List<String> values) {
                return values.size() + " values: " + String.join(", ", values);
            }
        }
        server = ServerUtils.httpsServerForTest()
            .addHandler(restHandler(new ValuesResource())
                .withCollectionParameterStrategy(CollectionParameterStrategy.SPLIT_ON_COMMA)
            )
            .start();
        try (Response resp = call(request(server.uri().resolve("/values")))) {
            assertThat(resp.body().string(), equalTo("0 values: "));
        }
        try (Response resp = call(request(server.uri().resolve("/values?value=")))) {
            assertThat(resp.body().string(), equalTo("0 values: "));
        }
        try (Response resp = call(request(server.uri().resolve("/values?value=,%2C")))) {
            assertThat(resp.body().string(), equalTo("0 values: "));
        }
        try (Response resp = call(request(server.uri().resolve("/values?value=one,two,three")))) {
            assertThat(resp.body().string(), equalTo("3 values: one, two, three"));
        }
        try (Response resp = call(request(server.uri().resolve("/values?value=one")))) {
            assertThat(resp.body().string(), equalTo("1 values: one"));
        }
        try (Response resp = call(request(server.uri().resolve("/values?value=%20one%20,%20")))) {
            assertThat(resp.body().string(), equalTo("1 values: one"));
        }
        try (Response resp = call(request(server.uri().resolve("/values?value=one%2Ctwo%2Cthree")))) {
            assertThat(resp.body().string(), equalTo("3 values: one, two, three"));
        }
        try (Response resp = call(request(server.uri().resolve("/values?value=one,two,three&value=four")))) {
            assertThat(resp.body().string(), equalTo("4 values: one, two, three, four"));
        }
    }

    @Test
    public void ifNoStrategySpecifiedButAPIHasCollectionsThenThrowError() throws Exception {
        @Path("values")
        class ValuesResource {
            @GET
            public String getIt(@QueryParam("value") List<String> values) {
                return values.size() + " values: " + String.join(", ", values);
            }
        }
        try {
            server = ServerUtils.httpsServerForTest()
                .addHandler(restHandler(new ValuesResource()))
                .start();
            Assert.fail("Exception should have been thrown");
        } catch (Exception e) {
            assertThat(e, instanceOf(IllegalStateException.class));
            assertThat(e.getMessage(), containsString("Please specify a string handling strategy for collections for querystring and header parameters"));
        }

    }

    @AfterEach
    public void stop() {
        scaffolding.MuAssert.stopAndCheck(server);
    }

}