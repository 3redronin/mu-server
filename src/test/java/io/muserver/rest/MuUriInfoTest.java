package io.muserver.rest;

import org.junit.Test;

import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.UriInfo;
import java.net.URI;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

public class MuUriInfoTest {
    static {
        MuRuntimeDelegate.ensureSet();
    }

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
    public void getPathParametersWorks() {
        MuUriInfo sample = create("http://example.org:8182/some%20path;param1;param2=value;param3=a%20list,of,values/path;param2=overridden;blah?query=yo#ha");
        MultivaluedMap<String, String> expectedParams = new MultivaluedHashMap<>();
        expectedParams.add("param1", "");
        expectedParams.add("blah", "");
        expectedParams.add("param2", "overridden");
        expectedParams.addAll("param3", "a list", "of", "values");
        assertThat(sample.getPathParameters(), equalTo(expectedParams));
        assertThat(sample.getPathParameters(), equalTo(sample.getPathParameters(true)));

        expectedParams.replace("param3", asList("a%20list", "of", "values"));
        assertThat(sample.getPathParameters(false), equalTo(expectedParams));
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
        return new MuUriInfo(baseUri1, fullUri, fullUri.getRawPath().substring(baseUri1.getRawPath().length()), emptyList(), emptyList());
    }

    private static URI uri(String baseUri) {
        return URI.create(baseUri);
    }

}