package io.muserver.rest;

import io.muserver.MuRequest;
import io.muserver.MuServer;
import io.muserver.Mutils;
import jakarta.ws.rs.*;
import jakarta.ws.rs.container.*;
import jakarta.ws.rs.core.*;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static io.muserver.ContextHandlerBuilder.context;
import static io.muserver.rest.MuRuntimeDelegate.MU_REQUEST_PROPERTY;
import static io.muserver.rest.MuRuntimeDelegate.RESOURCE_INFO_PROPERTY;
import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;
import static scaffolding.ServerUtils.httpsServerForTest;

public class FilterTest {

    private MuServer server;
    private final List<String> received = new ArrayList<>();

    @Path("something")
    class TheWay {
        @POST
        public String itMoves() {
            return "Hello";
        }
    }

    @PreMatching
    class MethodChangingFilter implements ContainerRequestFilter {
        @Override
        public void filter(ContainerRequestContext requestContext) throws IOException {
            received.add("REQUEST " + requestContext);
            requestContext.setMethod("POST");
            requestContext.setRequestUri(URI.create("something"));
            requestContext.setProperty("a-property", "a-value");
        }
    }

    class LoggingFilter implements ContainerRequestFilter, ContainerResponseFilter {

        @Override
        public void filter(ContainerRequestContext requestContext) throws IOException {
            received.add("REQUEST " + requestContext + " - " + requestContext.getProperty("a-property"));
        }

        @Override
        public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) throws IOException {
            received.add("RESPONSE " + responseContext);
        }
    }


    @Test
    public void requestUrisAndMethodsCanBeChangedSoThatThingsCanMatch() throws IOException {
        LoggingFilter loggingFilter = new LoggingFilter();
        server = httpsServerForTest()
            .addHandler(
                restHandler(new TheWay())
                    .addRequestFilter(loggingFilter)
                    .addResponseFilter(loggingFilter)
                    .addRequestFilter(new MethodChangingFilter())
            ).start();

        try (Response resp = call(request(server.uri().resolve("/blah")))) {
            assertThat(resp.body().string(), is("Hello"));
        }
        assertThat(received, contains(
            "REQUEST GET " + server.uri().resolve("/blah"),
            "REQUEST POST " + server.uri().resolve("/something") + " - a-value",
            "RESPONSE OK"
        ));
    }

    @Test
    public void requestContextPropertiesAreSharedWithRequestAttributes() throws IOException {

        @Path("/blah")
        class Blah {
            @GET
            public String hi(@Context MuRequest mur, @Context ContainerRequestContext crc) {
                MuRequest murequest = (MuRequest) mur.attribute("murequest");
                MuRequest murequest2 = (MuRequest) crc.getProperty("murequest");
                return "MUR: " + murequest.method() + " " + murequest.attribute("hello") + " "
                    + murequest.attribute("hello2") + " " + murequest.attribute("hello3")
                    + " CRC: " + murequest2.method() + " " + crc.getProperty("hello") + " "
                    + crc.getProperty("hello2") + " " + crc.getProperty("hello3");
            }
        }

        AtomicReference<Throwable> error = new AtomicReference<>();
        server = httpsServerForTest()
            .addHandler((request, response) -> {
                request.attribute("murequest", request);
                request.attribute("hello", "world");
                request.attribute("hello2", "world");
                return false;
            })
            .addHandler(
                restHandler()
                    .addRequestFilter(requestContext -> {
                        try {
                            try {
                                requestContext.getPropertyNames().add("shouldnothappen");
                                Assertions.fail("Should not have worked");
                            } catch (UnsupportedOperationException e) {
                                // expected
                            }
                            assertThat(requestContext.getPropertyNames(), containsInAnyOrder("murequest", "hello", "hello2", MU_REQUEST_PROPERTY, RESOURCE_INFO_PROPERTY));
                            requestContext.removeProperty("hello");
                            requestContext.setProperty("hello2", null);
                            requestContext.setProperty("hello3", "temp");
                            requestContext.setProperty("hello3", "hello3");
                        } catch (Throwable e) {
                            error.set(e);
                        }
                    })
                    .addResource(new Blah())
            ).start();

        assertThat(error.get(), is(nullValue()));
        try (Response resp = call(request(server.uri().resolve("/blah")))) {
            assertThat(error.get(), is(nullValue()));
            assertThat(resp.body().string(), is("MUR: GET null null hello3 CRC: GET null null hello3"));
        }
        assertThat(error.get(), is(nullValue()));
    }

    @Test
    public void itWorksWithContextsToo() throws IOException {
        LoggingFilter loggingFilter = new LoggingFilter();
        server = httpsServerForTest()
            .addHandler(context("in a context")
                .addHandler(
                    restHandler(new TheWay())
                        .addRequestFilter(new MethodChangingFilter())
                        .addRequestFilter(loggingFilter)
                )).start();
        try (Response resp = call(request().url(server.uri().resolve("/in%20a%20context/blah").toString()))) {
            assertThat(resp.body().string(), is("Hello"));
        }
        assertThat(received, contains(
            "REQUEST GET " + server.uri().resolve("/in%20a%20context/blah"),
            "REQUEST POST " + server.uri().resolve("/in%20a%20context/something") + " - a-value"
        ));
    }

    @Test
    public void pathParamsAreAvailableInFilters() throws Exception {
        @Path("something/{classLevel}/{repeat}")
        class SomeResource {
            @GET
            @Path("/and/{methodLevel}/{repeat}")
            public String hi(@Context ContainerRequestContext rc) {
                return rc.getProperty("added") + " ";
            }

        }
        server = httpsServerForTest()
            .addHandler(restHandler(new SomeResource()).addRequestFilter(requestContext -> {
                    UriInfo info = requestContext.getUriInfo();
                    boolean decode = "decode it".equals(info.getQueryParameters().get("decode").get(0));
                    MultivaluedMap<String, String> pathParameters = info.getPathParameters(decode);
                requestContext.setProperty("added", info.getPath(decode) + " " +
                    String.join(",", pathParameters.get("classLevel")) + " " +
                    String.join(",", pathParameters.get("repeat")) + " " +
                    String.join(",", pathParameters.get("methodLevel")));
            })
            )
            .start();
        try (Response resp = call(request(server.uri().resolve("/something/class%20param/repeat%20value/and/method%20param/repeat%20value?decode=decode%20it")))) {
            assertThat(resp.body().string(), is("something/class param/repeat value/and/method param/repeat value class param repeat value method param "));
        }

        try (Response resp = call(request(server.uri().resolve("/something/class%20param/repeat%20value/and/method%20param/repeat%20value?decode=no%20decode")))) {
            assertThat(resp.body().string(), is("something/class%20param/repeat%20value/and/method%20param/repeat%20value class%20param repeat%20value method%20param "));
        }


    }

    @Test
    public void theInputStreamCanBeSwappedOut() throws IOException {
        @Path("/echo")
        class Something {
            @POST
            public String echo(String body) {
                return body;
            }
        }

        @PreMatching
        class RequestInputChanger implements ContainerRequestFilter {
            public void filter(ContainerRequestContext requestContext) throws IOException {
                if (requestContext.hasEntity()) {
                    String originalText;
                    try (InputStream original = requestContext.getEntityStream()) {
                        ByteArrayOutputStream originalContent = new ByteArrayOutputStream();
                        Mutils.copy(original, originalContent, 8192);
                        originalText = originalContent.toString("UTF-8");
                    }
                    InputStream replacement = new ByteArrayInputStream(originalText.toUpperCase().getBytes("UTF-8"));
                    requestContext.setEntityStream(replacement);
                }
            }
        }

        server = httpsServerForTest()
            .addHandler(
                restHandler(new Something())
                    .addRequestFilter(new RequestInputChanger())
            ).start();
        try (Response resp = call(request()
            .url(server.uri().resolve("/echo").toString())
            .post(new RequestBody() {
                public MediaType contentType() {
                    return MediaType.parse("text/plain");
                }

                public void writeTo(BufferedSink sink) throws IOException {
                    sink.writeUtf8("Hello there");
                }
            })
        )) {
            assertThat(resp.body().string(), is("HELLO THERE"));
        }
    }


    @Test
    public void requestsCanBeAborted() throws IOException {
        @Path("something")
        class TheWay {
            @GET
            public String itMoves() {
                return "Not called";
            }
        }
        @PreMatching
        class MethodChangingFilter implements ContainerRequestFilter {
            @Override
            public void filter(ContainerRequestContext requestContext) throws IOException {
                requestContext.abortWith(jakarta.ws.rs.core.Response.status(409).entity("Blocked!").build());
            }
        }
        server = httpsServerForTest()
            .addHandler(
                restHandler(new TheWay())
                    .addRequestFilter(new MethodChangingFilter())
            ).start();
        try (Response resp = call(request().url(server.uri().resolve("/something").toString()))) {
            assertThat(resp.code(), is(409));
            assertThat(resp.body().string(), is("Blocked!"));
            assertThat(resp.header("content-type"), is("text/plain;charset=utf-8"));
        }
    }


    @Test
    public void ifExceptionIsThrownThenNormalExceptionHandlingHappens() throws IOException {
        @Path("something")
        class TheWay {
            @GET
            public String itMoves() {
                return "Not called";
            }
        }
        @PreMatching
        class MethodChangingFilter implements ContainerRequestFilter {
            @Override
            public void filter(ContainerRequestContext requestContext) throws IOException {
                throw new BadRequestException("Bad!!!");
            }
        }
        server = httpsServerForTest()
            .addHandler(
                restHandler(new TheWay())
                    .addRequestFilter(new MethodChangingFilter())
            ).start();
        try (Response resp = call(request().url(server.uri().resolve("/something").toString()))) {
            assertThat(resp.code(), is(400));
            assertThat(resp.body().string(), is("<h1>400 Bad Request</h1><p>Bad!!!</p>"));
        }
    }

    @Test
    public void exceptionMappersDoNotCatchAFiltersAbortWithResponse() throws IOException {
        @Path("something")
        class TheWay {
            @GET
            public String itMoves() {
                return "Not called";
            }
        }
        class AbortFilter implements ContainerRequestFilter {
            @Override
            public void filter(ContainerRequestContext requestContext) throws IOException {
                requestContext.abortWith(jakarta.ws.rs.core.Response.status(401).entity("No auth!!").build());
            }
        }
        server = httpsServerForTest()
            .addHandler(
                restHandler(new TheWay())
                    .addRequestFilter(new AbortFilter())
                    .addExceptionMapper(Exception.class, exception -> jakarta.ws.rs.core.Response.serverError().entity("Server error").build())
            )
            .start();
        try (Response resp = call(request().url(server.uri().resolve("/something").toString()))) {
            assertThat(resp.body().string(), is("No auth!!"));
            assertThat(resp.code(), is(401));
            assertThat(resp.header("content-type"), is("text/plain;charset=utf-8"));
        }
    }


    @Test
    public void responseFiltersCanChangeHeadersAndCookiesAndResponseCodesAndEntitiesEven() throws IOException {
        @Path("something")
        class TheWay {
            @GET
            @Produces("text/html")
            public jakarta.ws.rs.core.Response itMoves() {
                return jakarta.ws.rs.core.Response.status(200)
                    .header("My-Header", "was-lowercase")
                    .entity("a lowercase string")
                    .cookie(new NewCookie("my-cookie", "cooke-value"))
                    .build();
            }
        }


        server = httpsServerForTest()
            .addHandler(
                restHandler(new TheWay())
                    .addResponseFilter(new ContainerResponseFilter() {
                        @Override
                        public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) throws IOException {
                            responseContext.setStatus(400);
                            responseContext.setEntity(12, new Annotation[0], jakarta.ws.rs.core.MediaType.TEXT_PLAIN_TYPE);
                            responseContext.getStringHeaders().putSingle("My-Header", responseContext.getHeaderString("My-Header").toUpperCase());
                            responseContext.getHeaders().put("My-Number", asList(1, 2, 3));
                        }
                    })
            ).start();
        try (Response resp = call(request().url(server.uri().resolve("/something").toString()))) {
            assertThat(resp.code(), is(400));
            assertThat(resp.header("Content-Type"), is("text/plain;charset=utf-8"));
            assertThat(resp.body().string(), is("12"));
        }
    }

    @Test
    public void theContainerRequestContextCanBeAccessedFromRestMethods() throws IOException {
        @Path("something")
        class TheWay {
            @GET
            @Produces("text/plain")
            public String itMoves(@Context ContainerRequestContext reqContext) {
                return reqContext.getProperty("one") + " "
                    + reqContext.getProperty("two") + " " + reqContext.getProperty("three") + " " + reqContext.getProperty("four");
            }
        }

        server = httpsServerForTest()
            .addHandler(
                restHandler(new TheWay())
                    .addRequestFilter(requestContext -> {
                        requestContext.setProperty("one", "oneandtwoprop");
                        requestContext.setProperty("two", requestContext.getProperty("one"));
                        requestContext.setProperty("three", "temp");
                    })
                    .addRequestFilter(requestContext -> {
                        requestContext.removeProperty("three");
                    })
            ).start();
        try (Response resp = call(request().url(server.uri().resolve("/something").toString()))) {
            assertThat(resp.body().string(), is("oneandtwoprop oneandtwoprop null null"));
        }
    }

    @Test
    public void resourceInfoAndMuRequestCanBeFoundOnPreMatchingFilters() throws IOException {

        @Path("/blah")
        class Blah {
            @GET
            public void hello() {
            }
        }

        @PreMatching
        class PreMatchingFilter implements ContainerRequestFilter {

            @Override
            public void filter(ContainerRequestContext requestContext) throws IOException {
                MuRequest muRequest = (MuRequest) requestContext.getProperty(MU_REQUEST_PROPERTY);
                ResourceInfo resourceInfo = (ResourceInfo) requestContext.getProperty(MuRuntimeDelegate.RESOURCE_INFO_PROPERTY);
                requestContext.abortWith(jakarta.ws.rs.core.Response.ok()
                    .entity(
                        "properties=" + requestContext.getPropertyNames().stream().sorted().collect(Collectors.joining(", ")) +
                            " and method=" + muRequest.method() + " and "
                            + resourceInfo.getResourceClass() + " / " + resourceInfo.getResourceMethod())
                    .build());
            }
        }

        server = httpsServerForTest()
            .addHandler(
                restHandler(new Blah())
                    .addRequestFilter(new PreMatchingFilter())
            ).start();

        try (Response resp = call(request(server.uri().resolve("/blah")))) {
            assertThat(resp.body().string(), is("properties=" + MU_REQUEST_PROPERTY + ", " + RESOURCE_INFO_PROPERTY + " and method=GET and null / null"));
        }
    }

    @Test
    public void resourceInfoAndMuRequestCanBeFoundOnPostMatchingFilters() throws IOException {

        @Path("/blah")
        class Blah {
            @GET
            public void hello() {
            }
        }

        class PostMatchingFilter implements ContainerRequestFilter {

            @Override
            public void filter(ContainerRequestContext requestContext) throws IOException {
                MuRequest muRequest = (MuRequest) requestContext.getProperty(MU_REQUEST_PROPERTY);
                ResourceInfo resourceInfo = (ResourceInfo) requestContext.getProperty(MuRuntimeDelegate.RESOURCE_INFO_PROPERTY);
                requestContext.abortWith(jakarta.ws.rs.core.Response.ok()
                    .entity(
                        "properties=" + requestContext.getPropertyNames().stream().sorted().collect(Collectors.joining(", ")) +
                            " and method=" + muRequest.method() + " and "
                            + resourceInfo.getResourceClass() + " / " + resourceInfo.getResourceMethod().getName())
                    .build());
            }
        }

        server = httpsServerForTest()
            .addHandler(
                restHandler(new Blah())
                    .addRequestFilter(new PostMatchingFilter())
            ).start();

        try (Response resp = call(request(server.uri().resolve("/blah")))) {
            assertThat(resp.body().string(), is("properties=" + MU_REQUEST_PROPERTY + ", " + RESOURCE_INFO_PROPERTY + " and method=GET and " + Blah.class + " / hello"));
        }
    }

    @Test
    public void resourceInfoAndMuRequestCanBeFoundOnResponseFilters() throws IOException {

        @Path("/blah")
        class Blah {
            @GET
            public void hello() {
            }
        }
        StringBuilder result = new StringBuilder();

        class ResponseFilter implements ContainerResponseFilter {

            @Override
            public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) throws IOException {
                MuRequest muRequest = (MuRequest) requestContext.getProperty(MU_REQUEST_PROPERTY);
                ResourceInfo resourceInfo = (ResourceInfo) requestContext.getProperty(MuRuntimeDelegate.RESOURCE_INFO_PROPERTY);
                result.append("properties=").append(requestContext.getPropertyNames().stream().sorted().collect(Collectors.joining(", "))).append(" and method=").append(muRequest.method()).append(" and ").append(resourceInfo.getResourceClass()).append(" / ").append(resourceInfo.getResourceMethod().getName());
            }
        }

        server = httpsServerForTest()
            .addHandler(
                restHandler(new Blah())
                    .addResponseFilter(new ResponseFilter())
            ).start();

        call(request(server.uri().resolve("/blah"))).close();
        assertThat(result.toString(), is("properties=" + MU_REQUEST_PROPERTY + ", " + RESOURCE_INFO_PROPERTY + " and method=GET and " + Blah.class + " / hello"));
    }

    @Test
    public void queryParamsCanBeChangedInPreFilters() throws Exception {

        @Path("blah")
        class Blah {
            @GET
            public String query(@QueryParam("unchanged") String unchanged, @QueryParam("removed") String removed, @QueryParam("added") String added, @QueryParam("changed") String changed, @QueryParam("list") List<String> list) {
                return unchanged + " " + removed + " " + added + " " + changed + " and list: " + String.join(", ", list);
            }
        }

        @PreMatching
        class QueryChanger implements ContainerRequestFilter {
            @Override
            public void filter(ContainerRequestContext requestContext) throws IOException {
                UriBuilder builder = requestContext.getUriInfo().getRequestUriBuilder();
                builder.replaceQueryParam("changed", "a changed value");
                builder.replaceQueryParam("removed");
                builder.queryParam("added", "something new");
                builder.replaceQueryParam("list", requestContext.getUriInfo().getQueryParameters().get("list").stream().map(String::toUpperCase).toArray());
                requestContext.setRequestUri(builder.build());
            }
        }

        server = httpsServerForTest()
            .addHandler(
                context("api").addHandler(
                    restHandler(new Blah())
                        .withCollectionParameterStrategy(CollectionParameterStrategy.NO_TRANSFORM)
                        .addRequestFilter(new QueryChanger())
                )
            ).start();
        try (Response resp = call(request(server.uri().resolve("/api/blah?unchanged=original%20value&removed=bye&changed=another%20original&list=value%20one&list=value%20two")))) {
            assertThat(resp.body().string(), is("original value null something new a changed value and list: VALUE ONE, VALUE TWO"));
        }
    }

    @Test
    public void headerParamsCanBeChangedInPreFilters() throws Exception {

        @Path("blah")
        class Blah {
            @GET
            public String header(@HeaderParam("unchanged") String unchanged,
                                 @HeaderParam("removed") String removed,
                                 @HeaderParam("added") String added,
                                 @HeaderParam("changed") String changed,
                                 @HeaderParam("list") List<String> list) {
                return unchanged + " " + removed + " " + added + " " + changed + " and list: " + String.join(", ", list);
            }
        }

        @PreMatching
        class HeaderChanger implements ContainerRequestFilter {
            @Override
            public void filter(ContainerRequestContext requestContext) {
                MultivaluedMap<String, String> headers = requestContext.getHeaders();
                headers.replace("changed", Collections.singletonList("a changed value"));
                headers.remove("removed");
                headers.add("added", "something new");
                List<String> uppercasedList = headers.get("list").stream().map(String::toUpperCase).collect(Collectors.toList());
                headers.put("list", uppercasedList);
            }
        }

        server = httpsServerForTest()
            .addHandler(
                context("api").addHandler(
                    restHandler(new Blah())
                        .withCollectionParameterStrategy(CollectionParameterStrategy.NO_TRANSFORM)
                        .addRequestFilter(new HeaderChanger())
                )
            ).start();
        try (Response resp = call(request(server.uri().resolve("/api/blah"))
            .header("unchanged", "original value")
            .header("removed", "bye")
            .header("changed", "another original")
            .header("list", "value one")
            .addHeader("list", "value two")
        )) {
            assertThat(resp.body().string(), is("original value null something new a changed value and list: VALUE ONE, VALUE TWO"));
        }
    }


    @Test
    public void headerParamsCanBeChangedInPostFilters() throws Exception {

        @Path("blah")
        class Blah {
            @GET
            public String header(@HeaderParam("unchanged") String unchanged,
                                 @HeaderParam("removed") String removed,
                                 @HeaderParam("added") String added,
                                 @HeaderParam("changed") String changed,
                                 @HeaderParam("list") List<String> list) {
                return unchanged + " " + removed + " " + added + " " + changed + " and list: " + String.join(", ", list);
            }
        }

        class HeaderChanger implements ContainerRequestFilter {
            @Override
            public void filter(ContainerRequestContext requestContext) {
                MultivaluedMap<String, String> headers = requestContext.getHeaders();
                headers.replace("changed", Collections.singletonList("a changed value"));
                headers.remove("removed");
                headers.add("added", "something new");
                List<String> uppercasedList = headers.get("list").stream().map(String::toUpperCase).collect(Collectors.toList());
                headers.put("list", uppercasedList);
            }
        }

        server = httpsServerForTest()
            .addHandler(
                context("api").addHandler(
                    restHandler(new Blah())
                        .withCollectionParameterStrategy(CollectionParameterStrategy.NO_TRANSFORM)
                        .addRequestFilter(new HeaderChanger())
                )
            ).start();
        try (Response resp = call(request(server.uri().resolve("/api/blah"))
            .header("unchanged", "original value")
            .header("removed", "bye")
            .header("changed", "another original")
            .header("list", "value one")
            .addHeader("list", "value two")
        )) {
            assertThat(resp.body().string(), is("original value null something new a changed value and list: VALUE ONE, VALUE TWO"));
        }
    }

    @AfterEach
    public void stopIt() {
        scaffolding.MuAssert.stopAndCheck(server);
    }

}
