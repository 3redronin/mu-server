package io.muserver.rest;

import io.muserver.MuServer;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

import static io.muserver.ContextHandlerBuilder.context;
import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;
import static scaffolding.MuAssert.stopAndCheck;
import static scaffolding.ServerUtils.httpsServerForTest;

public class MuUriInfoTest {
    static {
        MuRuntimeDelegate.ensureSet();
    }

    private MuServer server;

    @Test
    public void canCreateBuilders() {
        UriInfo uriInfo = create("https://example.org/hey-man?blah=ha#yo");
        assertThat(uriInfo.getAbsolutePathBuilder().build(),
            equalTo(uri("https://example.org/hey-man")));
    }

    @Test
    public void resolveTest() {
        UriInfo uriInfo = create("https://example.org/?blah=ha#yo");
        assertThat(uriInfo.resolve(uri("/hey-man")), equalTo(uri("https://example.org/hey-man")));
    }

    @Test
    public void relativizeWorksAsPerJavaDoc() {
        assertThat(create("http://example.com:8080/app/root/a/b/c/resource.html", "http://example.com:8080/app/root/")
                .relativize(uri("a/b/c/d/file.txt")),
            equalTo(uri("d/file.txt")));
    }

    @Test
    public void relativizeIfAnyPartOfSuppliedHostOrPortIsDifferentThenReturnSupplied() {
        assertThat(create("http://example.com:8080/app/root/a/b/c/resource.html", "http://example.com:8080/app/root/")
                .relativize(uri("http://example2.com:9090/app2/root2/a/d/file.txt")),
            equalTo(uri("http://example2.com:9090/app2/root2/a/d/file.txt")));
    }

    @Test
    public void relativizeSuppliedURLCanBeAbsolute() {
        assertThat(create("http://example.com:8080/app/root/a/b/c/resource.html", "http://example.com:8080/app/root/")
                .relativize(uri("http://example.com:8080/app/root/a/b/c/d/file.txt")),
            equalTo(uri("d/file.txt")));
    }

    @Test
    public void getPathWorks() {
        MuUriInfo sample = create("http://example.org:8182/some%20path/path?query=yo#ha");
        assertThat(sample.getPath(), equalTo("some path/path"));
        assertThat(sample.getPath(), equalTo(sample.getPath(true)));
        assertThat(sample.getPath(false), equalTo("some%20path/path"));
    }

    @Test
    public void getAbsolutePathWorks() {
        MuUriInfo sample = create("http://example.org:8182/some%20path/path?query=yo#ha");
        assertThat(sample.getAbsolutePath(), equalTo(URI.create("http://example.org:8182/some%20path/path")));
    }


    @Test
    public void getPathSegmentsWorks() {
        MuUriInfo sample = create("http://example.org:8182/some%20path/path?query=yo#ha");
        assertThat(sample.getPathSegments(), equalTo(asList(segment("some path"), segment("path"))));
        assertThat(sample.getPathSegments(), equalTo(sample.getPathSegments(true)));
        assertThat(sample.getPathSegments(false), equalTo(asList(segment("some%20path"), segment("path"))));
    }

    @Test
    public void getPathSegmentsWithPathParametersWorks() {
        MuUriInfo sample = create("http://example.org:8182/some%20path;param1;param2=value;param3=a%20list,of,values/path?query=yo#ha");
        MultivaluedMap<String, String> expectedParams = new MultivaluedHashMap<>();
        expectedParams.add("param1", "");
        expectedParams.add("param2", "value");
        expectedParams.addAll("param3", "a list", "of", "values");
        assertThat(sample.getPathSegments(), equalTo(asList(segment("some path", expectedParams), segment("path"))));
        assertThat(sample.getPathSegments(), equalTo(sample.getPathSegments(true)));

        expectedParams.replace("param3", asList("a%20list", "of", "values"));
        assertThat(sample.getPathSegments(false), equalTo(asList(segment("some%20path", expectedParams), segment("path"))));
    }

    @Test
    public void getPathParametersIsEmptyInPreMatching() {
        MuUriInfo sample = create("http://example.org:8182/some%20path;param1;param2=value;param3=a%20list,of,values/path;param2=overridden;blah?query=yo#ha");
        assertThat(sample.getPathParameters(), Matchers.anEmptyMap());
    }

    @Test
    public void getQueryParametersWorks() {
        MuUriInfo sample = create("http://example.org:8182/?bye=Zai%20Jian&hi=nihao&hi=néih+hóu#hai");
        MultivaluedMap<String, String> expectedParams = new MultivaluedHashMap<>();
        expectedParams.add("bye", "Zai Jian");
        expectedParams.addAll("hi", "nihao", "néih hóu");
        assertThat(sample.getQueryParameters(), equalTo(expectedParams));
        assertThat(sample.getQueryParameters(), equalTo(sample.getQueryParameters(true)));

        expectedParams = new MultivaluedHashMap<>();
        expectedParams.add("bye", "Zai%20Jian");
        expectedParams.addAll("hi", "nihao", "n%C3%A9ih%20h%C3%B3u");
        assertThat(sample.getQueryParameters(false), equalTo(expectedParams));
    }

    @Test
    public void uriInfoInAJaxRsContextReturnsCorrectValues() {

        AtomicReference<UriInfo> uriRef = new AtomicReference<>();

        @Path("/hello%20resource")
        class Hello {

            @Path("/some path/{name}")
            @GET
            public void hello(@PathParam("name") String name, @Context UriInfo uriInfo) {
                uriRef.set(uriInfo);
            }

        }
        Hello helloResource = new Hello();
        server = httpsServerForTest()
            .addHandler(
                context("my app").addHandler(restHandler(helloResource))
            )
            .start();

        call(request(server.uri().resolve("/my%20app/hello%20resource;colour=light%20red/some%20path;colour=dark%20blue;size=medium/world%20cup;date=to%20morrow?some%20name=some%20value"))).close();

        UriInfo uriInfo = uriRef.get();
        assertThat(uriInfo, not(nullValue()));

        assertThat(uriInfo.getMatchedURIs(true), contains("hello resource;colour=light red/some path;colour=dark blue;size=medium/world cup;date=to morrow", "hello resource;colour=light red"));
        assertThat(uriInfo.getMatchedURIs(false), contains("hello%20resource;colour=light%20red/some%20path;colour=dark%20blue;size=medium/world%20cup;date=to%20morrow", "hello%20resource;colour=light%20red"));
        assertThat(uriInfo.getPath(true), equalTo("hello resource;colour=light red/some path;colour=dark blue;size=medium/world cup;date=to morrow"));
        assertThat(uriInfo.getPath(false), equalTo("hello%20resource;colour=light%20red/some%20path;colour=dark%20blue;size=medium/world%20cup;date=to%20morrow"));
        assertThat(uriInfo.getAbsolutePath(), equalTo(server.uri().resolve("/my%20app/hello%20resource;colour=light%20red/some%20path;colour=dark%20blue;size=medium/world%20cup;date=to%20morrow")));
        assertThat(uriInfo.getBaseUri(), equalTo(server.uri().resolve("/my%20app/")));
        assertThat(uriInfo.getRequestUri(), equalTo(server.uri().resolve("/my%20app/hello%20resource;colour=light%20red/some%20path;colour=dark%20blue;size=medium/world%20cup;date=to%20morrow?some%20name=some%20value")));
        assertThat(uriInfo.getMatchedResources(), contains(helloResource));
        assertMapHasOneEntry(uriInfo.getPathParameters(true), "name", "world cup");
        assertMapHasOneEntry(uriInfo.getPathParameters(false), "name", "world%20cup");
        assertThat(uriInfo.getPathSegments(true), contains(new MuPathSegment("hello resource", oneItem("colour", "light red")), new MuPathSegment("some path", twoItems("colour", "dark blue", "size", "medium")), new MuPathSegment("world cup", oneItem("date", "to morrow"))));
        assertThat(uriInfo.getPathSegments(false), contains(new MuPathSegment("hello%20resource", oneItem("colour", "light%20red")), new MuPathSegment("some%20path", twoItems("colour", "dark%20blue", "size", "medium")), new MuPathSegment("world%20cup", oneItem("date", "to%20morrow"))));
        assertThat(uriInfo.resolve(URI.create("something")), equalTo(server.uri().resolve("/my%20app/something")));
        assertThat(uriInfo.relativize(URI.create("relativize")), equalTo(server.uri().resolve("/my%20app/relativize")));
        assertMapHasOneEntry(uriInfo.getQueryParameters(true), "some name", "some value");
        assertMapHasOneEntry(uriInfo.getQueryParameters(false), "some name", "some%20value");

    }

    @Test
    public void preMatchFiltersHaveMostUriInfo() {

        AtomicReference<UriInfo> uriRef = new AtomicReference<>();
        @Path("/dummy")
        class Hello {
        }
        @PreMatching
        class PreMatchFilter implements ContainerRequestFilter {
            @Override
            public void filter(ContainerRequestContext requestContext) {
                uriRef.set(requestContext.getUriInfo());
            }
        }
        Hello helloResource = new Hello();
        server = httpsServerForTest()
            .addHandler(
                context("my app").addHandler(restHandler(helloResource).addRequestFilter(new PreMatchFilter()))
            )
            .start();

        call(request(server.uri().resolve("/my%20app/hello%20resource;colour=light%20red/some%20path;colour=dark%20blue;size=medium/world%20cup;date=to%20morrow?some%20name=some%20value"))).close();

        UriInfo uriInfo = uriRef.get();
        assertThat(uriInfo, not(nullValue()));

        assertThat(uriInfo.getMatchedURIs(true), empty());
        assertThat(uriInfo.getMatchedURIs(false), empty());
        assertThat(uriInfo.getPath(true), equalTo("hello resource;colour=light red/some path;colour=dark blue;size=medium/world cup;date=to morrow"));
        assertThat(uriInfo.getPath(false), equalTo("hello%20resource;colour=light%20red/some%20path;colour=dark%20blue;size=medium/world%20cup;date=to%20morrow"));
        assertThat(uriInfo.getAbsolutePath(), equalTo(server.uri().resolve("/my%20app/hello%20resource;colour=light%20red/some%20path;colour=dark%20blue;size=medium/world%20cup;date=to%20morrow")));
        assertThat(uriInfo.getBaseUri(), equalTo(server.uri().resolve("/my%20app/")));
        assertThat(uriInfo.getRequestUri(), equalTo(server.uri().resolve("/my%20app/hello%20resource;colour=light%20red/some%20path;colour=dark%20blue;size=medium/world%20cup;date=to%20morrow?some%20name=some%20value")));
        assertThat(uriInfo.getMatchedResources(), empty());
        assertThat(uriInfo.getPathParameters(true).entrySet(), empty());
        assertThat(uriInfo.getPathParameters(false).entrySet(), empty());
        assertThat(uriInfo.getPathSegments(true), contains(new MuPathSegment("hello resource", oneItem("colour", "light red")), new MuPathSegment("some path", twoItems("colour", "dark blue", "size", "medium")), new MuPathSegment("world cup", oneItem("date", "to morrow"))));
        assertThat(uriInfo.getPathSegments(false), contains(new MuPathSegment("hello%20resource", oneItem("colour", "light%20red")), new MuPathSegment("some%20path", twoItems("colour", "dark%20blue", "size", "medium")), new MuPathSegment("world%20cup", oneItem("date", "to%20morrow"))));
        assertThat(uriInfo.resolve(URI.create("something")), equalTo(server.uri().resolve("/my%20app/something")));
        assertThat(uriInfo.relativize(URI.create("relativize")), equalTo(server.uri().resolve("/my%20app/relativize")));
        assertMapHasOneEntry(uriInfo.getQueryParameters(true), "some name", "some value");
        assertMapHasOneEntry(uriInfo.getQueryParameters(false), "some name", "some%20value");
    }


    private static MultivaluedHashMap<String, String> oneItem(String name, String value) {
        MultivaluedHashMap<String, String> map = new MultivaluedHashMap<>();
        map.add(name, value);
        return map;
    }
    private static MultivaluedHashMap<String, String> twoItems(String name, String value, String name2, String value2) {
        MultivaluedHashMap<String, String> map = oneItem(name, value);
        map.add(name2, value2);
        return map;
    }


    private void assertMapHasOneEntry(MultivaluedMap<String,String> actual, String expectedName, String expectedValue) {
        assertThat(actual.toString(), actual.entrySet(), hasSize(1));
        assertThat(actual.toString(), actual.get(expectedName), contains(expectedValue));
    }


    private static MuPathSegment segment(String path) {
        return new MuPathSegment(path, new MultivaluedHashMap<>());
    }
    private static MuPathSegment segment(String path, MultivaluedMap<String,String> params) {
        return new MuPathSegment(path, params);
    }

    private static MuUriInfo create(String uri) {
        return create(uri, uri(uri).resolve("/").toString());
    }

    private static MuUriInfo create(String uri, String baseUri) {
        URI baseUri1 = uri(baseUri);
        URI fullUri = uri(uri);
        return new MuUriInfo(baseUri1, fullUri, fullUri.getRawPath().substring(baseUri1.getRawPath().length()), null);
    }

    private static URI uri(String baseUri) {
        return URI.create(baseUri);
    }

    @AfterEach
    public void stop() {
        stopAndCheck(server);
    }

}