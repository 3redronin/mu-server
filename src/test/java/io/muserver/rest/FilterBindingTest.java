package io.muserver.rest;

import io.muserver.MuServer;
import okhttp3.Response;
import org.junit.Test;

import javax.ws.rs.GET;
import javax.ws.rs.NameBinding;
import javax.ws.rs.Path;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.container.ContainerResponseContext;
import javax.ws.rs.container.ContainerResponseFilter;
import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.List;

import static io.muserver.MuServerBuilder.httpsServer;
import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class FilterBindingTest {

    @NameBinding
    @Target({ ElementType.TYPE, ElementType.METHOD })
    @Retention(value = RetentionPolicy.RUNTIME)
    public @interface Logged { }

    @Logged
    private class LoggingFilter implements ContainerRequestFilter, ContainerResponseFilter {
        final List<String> received = new ArrayList<>();

        @Override
        public void filter(ContainerRequestContext requestContext) {
            received.add("REQUEST");
        }

        @Override
        public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
            received.add("RESPONSE");
        }
    }

    @Test
    public void onlyMathodsMarkedWithNameBoundAnnotationsAreIntercepted() throws IOException {

        @Path("something")
        class TheWay {
            @GET
            @Path("she")
            @Logged
            public String moves() {
                return "she moves";
            }

            @GET
            @Path("like")
            public String noOther() {
                return "lover";
            }
        }

        LoggingFilter loggingFilter = new LoggingFilter();
        MuServer server = httpsServer()
            .addHandler(
                restHandler(new TheWay())
                    .addRequestFilter(loggingFilter)
                    .addResponseFilter(loggingFilter)
            ).start();
        try (Response resp = call(request().url(server.uri().resolve("/something/she").toString()))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), is("she moves"));
            assertThat(loggingFilter.received, contains("REQUEST", "RESPONSE"));
        }
        loggingFilter.received.clear();
        try (Response resp = call(request().url(server.uri().resolve("/something/like").toString()))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), is("lover"));
            assertThat(loggingFilter.received, empty());
        }
    }



    @Test
    public void ifTheClassIsMarkedThenMethodsInheritThat() throws IOException {

        @Logged
        @Path("something")
        class TheWay {
            @GET
            public String moves() {
                return "moves";
            }
        }

        LoggingFilter loggingFilter = new LoggingFilter();
        MuServer server = httpsServer()
            .addHandler(
                restHandler(new TheWay())
                    .addRequestFilter(loggingFilter)
                    .addResponseFilter(loggingFilter)
            ).start();
        try (Response resp = call(request().url(server.uri().resolve("/something").toString()))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), is("moves"));
            assertThat(loggingFilter.received, contains("REQUEST", "RESPONSE"));
        }
    }

}
