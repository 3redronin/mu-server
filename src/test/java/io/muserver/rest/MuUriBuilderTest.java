package io.muserver.rest;

import org.junit.Ignore;
import org.junit.Test;

import javax.ws.rs.core.UriBuilder;
import java.net.URI;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

public class MuUriBuilderTest {
    static {
        MuRuntimeDelegate.ensureSet();
    }


    @Test
    public void cloneTest() {
    }

    @Test
    public void uri() {
        UriBuilder builder = MuUriBuilder.fromUri(u("http://example.org/blah/?a=b"));
        assertThat(builder.build(), equalTo(u("http://example.org/blah?a=b")));
    }

    @Test
    @Ignore("not working yet")
    public void uri1() {
        assertThat(new MuUriBuilder().uri(u("http://example.org/blah/?a=b")).build(),
            equalTo(new MuUriBuilder().uri("http://example.org/blah?a=b").build()));
    }

    @Test
    public void scheme() {
        assertThat(MuUriBuilder.fromUri(u("http://example.org/blah/")).scheme("https").build().toString(),
            equalTo("https://example.org/blah"));
    }

    @Test
    public void schemeSpecificPart() {
        assertThat(MuUriBuilder.fromUri(u("https://example.org/blah/"))
                .schemeSpecificPart("localhost:8080/something/else").build().toString(),
            equalTo("https://localhost:8080/something/else"));
    }

    @Test
    public void userInfo() {
        UriBuilder builder = MuUriBuilder.fromUri(u("http://example.org/blah/")).userInfo("ha:ho");
        assertThat(builder.build().toString(), equalTo("http://ha:ho@example.org/blah"));
        assertThat(builder.userInfo(null).build().toString(), equalTo("http://example.org/blah"));
    }

    @Test
    public void host() {
        assertThat(MuUriBuilder.fromUri(u("http://example.org/blah/")).host("localhost").build().toString(),
            equalTo("http://localhost/blah"));
    }

    @Test
    public void port() {
        UriBuilder builder = MuUriBuilder.fromUri(u("http://example.org/blah/")).port(8080);
        assertThat(builder.build().toString(), equalTo("http://example.org:8080/blah"));
        assertThat(builder.port(-1).build().toString(), equalTo("http://example.org/blah"));
    }

    @Test
    public void replacePath() {
        URI uri = MuUriBuilder.fromUri(u("http://example.org/blah"))
            .replacePath("ha/har")
            .build();
        assertThat(uri.toString(), equalTo("http://example.org/ha/har"));
    }

    @Test
    public void pathsAreAppended() {
        URI uri = MuUriBuilder.fromUri(u("http://example.org/blah"))
            .path("ha/har")
            .path("hello world")
            .build();
        assertThat(uri.toString(), equalTo("http://example.org/blah/ha/har/hello%20world"));
    }

    @Test
    public void path1() {
    }

    @Test
    public void path2() {
    }

    @Test
    public void path3() {
    }

    @Test
    public void segment() {
        URI uri = MuUriBuilder.fromUri(u("http://example.org/blah"))
            .segment("ha/har", "hello world")
            .build();
        assertThat(uri.toString(), equalTo("http://example.org/blah/ha%2Fhar/hello%20world"));
    }

    @Test
    public void replaceMatrix() {
    }

    @Test
    public void matrixParam() {
    }

    @Test
    public void replaceMatrixParam() {
    }

    @Test
    public void replaceQueryWithNullValueClearsParams() {
        URI uri = MuUriBuilder.fromUri(u("http://example.org/blah?hah=ba"))
            .queryParam("a", "b")
            .replaceQuery(null)
            .build();
        assertThat(uri.toString(), equalTo("http://example.org/blah"));
    }

    @Test
    public void replaceQueryWithQueryStringParsesString() {
        URI uri = MuUriBuilder.fromUri(u("http://example.org/blah?hah=ba"))
            .replaceQuery("?hah=ha&b=a")
            .build();
        assertThat(uri.toString(), equalTo("http://example.org/blah?b=a&hah=ha"));
    }

    @Test
    public void queryParam() {
        URI uri = MuUriBuilder.fromUri(u("http://example.org/blah"))
            .queryParam("a", "b")
            .queryParam("c d", "d", "e f")
            .build();
        assertThat(uri.toString(), equalTo("http://example.org/blah?a=b&c%20d=d&c%20d=e%20f"));
    }

    @Test
    public void replaceQueryParam() {
        URI uri = MuUriBuilder.fromUri(u("http://example.org/blah?a=b&c=d&e=f"))
            .replaceQueryParam("a", "newA")
            .replaceQueryParam("c", null)
            .replaceQueryParam("g", "h")
            .build();
        assertThat(uri.toString(), equalTo("http://example.org/blah?a=newA&e=f&g=h"));
    }

    @Test
    public void fragment() {
        URI uri = MuUriBuilder.fromUri(u("http://example.org"))
            .fragment("hello")
            .build();
        assertThat(uri.toString(), equalTo("http://example.org/#hello"));
    }

    @Test
    public void resolveTemplate() {
    }

    @Test
    public void resolveTemplate1() {
    }

    @Test
    public void resolveTemplateFromEncoded() {
    }

    @Test
    public void resolveTemplates() {
    }

    @Test
    public void resolveTemplates1() {
    }

    @Test
    public void resolveTemplatesFromEncoded() {
    }

    @Test
    public void buildFromMap1() {
    }

    @Test
    public void buildFromEncodedMap() {
    }

    @Test
    public void buildFromEncoded() {
    }

    @Test
    public void toTemplate() {
    }

    private static URI u(String uri) {
        return URI.create(uri);
    }
}