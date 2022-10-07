package io.muserver.rest;

import io.muserver.MuServer;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response.Status;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import okhttp3.Response;
import org.junit.After;
import org.junit.Test;
import scaffolding.ServerUtils;

import java.net.URI;

import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static java.util.Collections.singletonList;
import static org.hamcrest.CoreMatchers.allOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class ExceptionsTest {
    private MuServer server;

    @Test
    public void notFoundExceptionsConvertTo404() throws Exception {
        @Path("samples")
        class Sample {
            @GET
            @Path("/custom-message")
            public String get() {
                throw new NotFoundException("This is a custom error message");
            }

            @GET
            @Path("/default-message")
            public String getDefault() {
                throw new NotFoundException();
            }
        }
        this.server = ServerUtils.httpsServerForTest().addHandler(restHandler(new Sample())).start();
        try (Response resp = call(request().url(server.uri().resolve("/samples/custom-message").toString()))) {
            assertThat(resp.code(), is(404));
            assertThat(resp.body().string(), allOf(
                containsString("<h1>404 Not Found</h1>"),
                containsString("<p>This is a custom error message</p>")));
        }
        try (Response resp = call(request().url(server.uri().resolve("/samples/default-message").toString()))) {
            assertThat(resp.code(), is(404));
            assertThat(resp.body().string(), allOf(
                containsString("<h1>404 Not Found</h1>"),
                containsString("<p>HTTP 404 Not Found</p>")));
        }
    }

    @Test
    public void redirectExceptionsWork() throws Exception {
        @Path("redirects")
        class Redirector {
            @GET
            @Path("/relative")
            public void relative() {
                throw new RedirectionException(Status.FOUND, URI.create("/see-other"));
            }

            @GET
            @Path("/absolute")
            public String absolute(@Context UriInfo uri) {
                throw new RedirectionException(Status.TEMPORARY_REDIRECT, uri.getRequestUri().resolve("/see-other"));
            }

            @GET
            @Path("/other-site")
            public void other() {
                throw new RedirectionException(Status.SEE_OTHER, URI.create("http://example.org/see-other"));
            }
        }
        this.server = ServerUtils.httpsServerForTest().addHandler(restHandler(new Redirector())).start();
        try (Response resp = call(request(server.uri().resolve("/redirects/relative")))) {
            assertThat(resp.code(), is(302));
            assertThat(resp.headers("location"), is(singletonList(server.uri().resolve("/see-other").toString())));
        }
        try (Response resp = call(request(server.uri().resolve("/redirects/absolute")))) {
            assertThat(resp.code(), is(307));
            assertThat(resp.headers("location"), is(singletonList(server.uri().resolve("/see-other").toString())));
        }
        try (Response resp = call(request(server.uri().resolve("/redirects/other-site")))) {
            assertThat(resp.code(), is(303));
            assertThat(resp.headers("location"), is(singletonList("http://example.org/see-other")));
        }
    }


    @Test
    public void ifNoSuitableMethodThen405Returned() throws Exception {
        @Path("samples")
        class Sample {
            @POST
            public String get() {
                throw new NotFoundException("This is ignored");
            }
        }
        this.server = ServerUtils.httpsServerForTest().addHandler(restHandler(new Sample())).start();
        try (Response resp = call(request().url(server.uri().resolve("/samples").toString()))) {
            assertThat(resp.code(), is(405));
            assertThat(resp.body().string(), containsString("405 Method Not Allowed"));
        }
    }

    @Test
    public void classMatchingWithNoSuitableMethodsThrows404() throws Exception {
        @Path("samples")
        class Sample {
            @GET
            @Path("get")
            public String get() {
                return "This should not be called";
            }
        }
        this.server = ServerUtils.httpsServerForTest().addHandler(restHandler(new Sample())).start();
        try (Response resp = call(request().url(server.uri().resolve("/samples").toString()))) {
            assertThat(resp.code(), is(404));
            assertThat(resp.body().string(), containsString("404 Not Found"));
        }
    }

    @Test
    public void clientExceptionsAre400s() throws Exception {
        @Path("samples")
        class Sample {
            @GET
            @Path("/custom-message")
            public String get() {
                throw new ClientErrorException("This is custom client error", 400);
            }

            @GET
            @Path("/default-message")
            public String getDefault() {
                throw new ClientErrorException(400);
            }
        }
        this.server = ServerUtils.httpsServerForTest().addHandler(restHandler(new Sample())).start();
        try (Response resp = call(request().url(server.uri().resolve("/samples/custom-message").toString()))) {
            assertThat(resp.code(), is(400));
            assertThat(resp.body().string(), allOf(
                containsString("<h1>400 Bad Request</h1>"),
                containsString("<p>This is custom client error</p>")));
        }
        try (Response resp = call(request().url(server.uri().resolve("/samples/default-message").toString()))) {
            assertThat(resp.code(), is(400));
            assertThat(resp.body().string(), allOf(
                containsString("<h1>400 Bad Request</h1>"),
                containsString("<p>HTTP 400 Bad Request</p>")));
        }
    }

    @Test
    public void customExceptionsFromSubResourcesWork() throws Exception {

        class CustomException extends RuntimeException {}

        class SubResource {
            @GET
            public void yo() {
                throw new CustomException();
            }
        }

        @Path("samples")
        class Sample {
            @Path("/sub")
            public SubResource getYo() {
                return new SubResource();
            }
        }
        this.server = ServerUtils.httpsServerForTest()
            .addHandler(
                restHandler(new Sample())
                .addExceptionMapper(CustomException.class, (ExceptionMapper<CustomException>) exception -> jakarta.ws.rs.core.Response.status(414).entity("Custom exception").build())
            )
            .start();
        try (Response resp = call(request(server.uri().resolve("/samples/sub")))) {
            assertThat(resp.code(), is(414));
            assertThat(resp.body().string(), containsString("Custom exception"));
        }
    }

    @After
    public void stop() {
        scaffolding.MuAssert.stopAndCheck(server);
    }

}
