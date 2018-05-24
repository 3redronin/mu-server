package io.muserver.rest;

import io.muserver.ContextHandlerBuilder;
import io.muserver.MuServer;
import io.muserver.SSLContextBuilder;
import okhttp3.Response;
import org.hamcrest.Matchers;
import org.junit.Test;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriInfo;
import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static io.muserver.MuServerBuilder.httpsServer;
import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class MuUriBuilderTest {
    static {
        MuRuntimeDelegate.ensureSet();
    }

    @Test
    public void prettyMuchEverythingCanHaveTemplateParameters() {
        UriBuilder builder = new MuUriBuilder()
            .scheme("http{s}")
            .userInfo("{name}:{password}")
            .host("www.example.{extension}")
            .path("some-{path}")
            .queryParam("{key}", "{value}")
            .fragment("frag{ment}");

        URI built = builder.build("s", "dan man", "p@sswor!", "org", "some thing", "a param", "a value", " a boo");
        assertThat(built.toString(),
            equalTo("https://dan%20man:p%40sswor%21@www.example.org/some-some%20thing?a%20param=a%20value#frag%20a%20boo"));

        assertThat(builder.toTemplate(),
            equalTo("http{s}://{name}:{password}@www.example.{extension}/some-{path}?{key}={value}#frag{ment}"));
    }

    @Test
    public void templateParameterValuesCanBeUrlEncodedOrNot() {
        UriBuilder builder = new MuUriBuilder()
            .scheme("http{s}")
            .userInfo("{name}:{password}")
            .host("www.{domain}.org")
            .path("some-{path}")
            .queryParam("{key}", "{value}")
            .fragment("frag{ment}");

        assertThat(builder.build("s", " %20%2G", " %20%2G", " %20%2G", " %20%2G", " %20%2G", " %20%2G", " %20%2G").toString(),
            equalTo("https://%20%2520%252G:%20%2520%252G@www.%20%2520%252G.org/some-%20%2520%252G?%20%2520%252G=%20%2520%252G#frag%20%2520%252G"));

        assertThat(builder.buildFromEncoded("s", " %20%2G", " %20%2G", " %20%2G", " %20%2G", " %20%2G", " %20%2G", " %20%2G").toString(),
            equalTo("https://%20%20%252G:%20%20%252G@www.%20%20%252G.org/some-%20%20%252G?%20%20%252G=%20%20%252G#frag%20%20%252G"));
    }


    @Test
    public void examplesFromInternet() {
        assertThat(UriBuilder.fromUri("http://localhost:8080").queryParam("name", "{value}").build("%20").toString(),
            equalTo("http://localhost:8080?name=%2520"));
        assertThat(UriBuilder.fromUri("http://localhost:8080").queryParam("name", "{value}").buildFromEncoded("%20").toString(),
            equalTo("http://localhost:8080?name=%20"));
        assertThat(UriBuilder.fromUri("http://localhost:8080").replaceQuery("name={value}").build("%20").toString(),
            equalTo("http://localhost:8080?name=%2520"));
        assertThat(UriBuilder.fromUri("http://localhost:8080").replaceQuery("name={value}").buildFromEncoded("%20").toString(),
            equalTo("http://localhost:8080?name=%20"));
        assertThat(UriBuilder.fromPath("{arg1}").build("foo#bar").toString(),
            equalTo("foo%23bar"));
        assertThat(UriBuilder.fromPath("{arg1}").fragment("{arg2}").build("foo", "bar").toString(),
            equalTo("foo#bar"));

        assertThat(UriBuilder.fromUri("http://localhost:8080").path("name/{value}").path("%20").build("%20").toString(),
            equalTo("http://localhost:8080/name/%2520/%20"));
        assertThat(UriBuilder.fromUri("http://localhost:8080").path("name/%20").path("%20").build().toString(),
            equalTo("http://localhost:8080/name/%20/%20"));
        assertThat(UriBuilder.fromUri("http://localhost:8080").path("name/{value}").path("%20").buildFromEncoded("%20").toString(),
            equalTo("http://localhost:8080/name/%20/%20"));
        assertThat(UriBuilder.fromUri("http://localhost:8080").path("name/%20").path("%20").buildFromEncoded().toString(),
            equalTo("http://localhost:8080/name/%20/%20"));
    }

    @Test
    public void buildWithEncodedOnlyAppliesToTemplateValues() {
        URI one = UriBuilder.fromUri("http://localhost:8080").queryParam("name", "%20").build();
        URI two = UriBuilder.fromUri("http://localhost:8080").queryParam("name", "%20").buildFromEncoded();
        URI three = UriBuilder.fromUri("http://localhost:8080").replaceQuery("name=%20").build();
        URI four = UriBuilder.fromUri("http://localhost:8080").replaceQuery("name=%20").buildFromEncoded();
        assertThat(one.toString(), equalTo("http://localhost:8080?name=%20"));
        assertThat(one, equalTo(two));
        assertThat(one, equalTo(three));
        assertThat(one, equalTo(four));
    }

    @Test
    public void cloneTest() {
        UriBuilder builder = MuUriBuilder.fromUri(u("http://example.org/blah/?a=b"));
        UriBuilder clone = builder.clone();
        assertThat(builder.build(), equalTo(clone.build()));
        builder.path("mutations");
        assertThat(builder.build(), not(equalTo(clone.build())));
    }

    @Test
    public void fromUriObject() {
        UriBuilder builder = MuUriBuilder.fromUri(u("http://example.org/blah/?a=b"));
        assertThat(builder.build(), equalTo(u("http://example.org/blah/?a=b")));
    }

    @Test
    public void fromUriTemplate() {
        assertThat(new MuUriBuilder().uri(u("http://user:pw@example.org:12000/blah/?a=b#hi")).build(),
            equalTo(new MuUriBuilder().uri("http://user:pw@example.org:12000/blah/?a=b#hi").build()));
    }

    @Test
    public void fromUriTemplateWithPlaceholders() {
        assertThat(new MuUriBuilder()
                .uri("http://user:pw@example.org:12000/a/{name}/{ name }/{ nameWithRegex : [0-9]+ }/?a=b#hi")
                .replacePath("/").build().toString(),
            equalTo("http://user:pw@example.org:12000/?a=b#hi"));
        assertThat(new MuUriBuilder()
                .uri("http://user:pw@example.org:12000/a/{name}/{ name }/{ nameWithRegex : [0-9]+ }/?a=b")
                .replacePath("/").build().toString(),
            equalTo("http://user:pw@example.org:12000/?a=b"));
        assertThat(new MuUriBuilder()
                .uri("http://user:pw@example.org:12000/a/{name}/{ name }/{ nameWithRegex : [0-9]+ }/#hi")
                .replacePath("/").build().toString(),
            equalTo("http://user:pw@example.org:12000/#hi"));
        assertThat(new MuUriBuilder()
                .uri("http://user:pw@example.org:12000/")
                .build().toString(),
            equalTo("http://user:pw@example.org:12000/"));
        assertThat(new MuUriBuilder()
                .uri("http://user:pw@example.org:12000")
                .build().toString(),
            equalTo("http://user:pw@example.org:12000"));
    }

    @Test
    public void scheme() {
        assertThat(MuUriBuilder.fromUri(u("http://example.org/blah")).scheme("https").build().toString(),
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
        UriBuilder builder = MuUriBuilder.fromUri(u("http://example.org/blah")).userInfo("ha:ho");
        assertThat(builder.build().toString(), equalTo("http://ha:ho@example.org/blah"));
        assertThat(builder.userInfo(null).build().toString(), equalTo("http://example.org/blah"));
    }

    @Test
    public void host() {
        assertThat(MuUriBuilder.fromUri(u("http://example.org/blah/")).host("localhost").build().toString(),
            equalTo("http://localhost/blah/"));
    }

    @Test
    public void port() {
        UriBuilder builder = MuUriBuilder.fromUri(u("http://example.org/blah")).port(8080);
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
    public void urlsCanBeRelative() {
        assertThat(UriBuilder.fromUri("/some/path").build().toString(), equalTo("/some/path"));
    }

    @Test
    public void itRemembersTrailingSlashes() {
        assertThat(UriBuilder.fromUri("http://localhost").build().toString(), equalTo("http://localhost"));
        assertThat(UriBuilder.fromUri("http://localhost/").build().toString(), equalTo("http://localhost/"));
    }

    @Test
    public void canLookUpResourcesBasedOnResourceClassesAndMethods() throws IOException {
        @Path("v1/fruits")
        class FruitResource {
            @GET
            @Path("{id}")
            public void getOne() {
            }
        }
        @Path("v1/dogs")
        class DogResource {
            @GET
            @Path("getResourceClass")
            public String getResourceClass(@Context UriInfo info) {
                return info.getBaseUriBuilder().path(FruitResource.class).build().toString();
            }

            @GET
            @Path("getResourceMethod")
            public String getResourceMethod(@Context UriInfo info) throws NoSuchMethodException {
                UriBuilder getOne = info.getBaseUriBuilder().path(FruitResource.class.getDeclaredMethod("getOne"));
                return getOne.build("some-id").toString();
            }

            @GET
            @Path("getResourceMethodByName")
            public String getResourceMethodByName(@Context UriInfo info) {
                return info.getBaseUriBuilder().path(FruitResource.class, "getOne").build("some-id").toString();
            }

        }

        assertThat(UriBuilder.fromResource(FruitResource.class).build().toString(), equalTo("v1/fruits"));
        assertThat(UriBuilder.fromMethod(FruitResource.class, "getOne").build("some thing").toString(), equalTo("v1/fruits/some%20thing"));

        MuServer server = httpsServer().withHttpsPort(15647).withHttpsConfig(SSLContextBuilder.unsignedLocalhostCert())
            .addHandler(ContextHandlerBuilder.context("api")
                .addHandler(RestHandlerBuilder.restHandler(new FruitResource(), new DogResource()).build()))
            .start();
        try {
            try (Response resp = call(request().url(server.uri().resolve("/api/v1/dogs/getResourceClass").toString()))) {
                assertThat(resp.body().string(), equalTo("https://localhost:15647/api/v1/fruits"));
            }
            try (Response resp = call(request().url(server.uri().resolve("/api/v1/dogs/getResourceMethodByName").toString()))) {
                assertThat(resp.body().string(), equalTo("https://localhost:15647/api/v1/fruits/some-id"));
            }
            try (Response resp = call(request().url(server.uri().resolve("/api/v1/dogs/getResourceMethod").toString()))) {
                assertThat(resp.body().string(), equalTo("https://localhost:15647/api/v1/fruits/some-id"));
            }
        } finally {
            server.stop();
        }
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
        UriBuilder uriBuilder = UriBuilder.fromPath("/hello");
        uriBuilder.matrixParam("color", "red", 12);
        uriBuilder.matrixParam("frank");
        uriBuilder.path("blah");
        uriBuilder.matrixParam("another", "some;thi=ng");
        uriBuilder.matrixParam("color", "black");

        assertThat(uriBuilder.clone().replaceMatrix("color=blue;rat=cat;rat=1").build().toString(),
            equalTo("/hello;color=red;color=12/blah;color=blue;rat=cat;rat=1"));

        assertThat(uriBuilder.clone().replaceMatrix(null).build().toString(),
            equalTo("/hello;color=red;color=12/blah"));

    }

    @Test
    public void matrixParam() {
        UriBuilder uriBuilder = UriBuilder.fromPath("/hello");
        uriBuilder.matrixParam("color", "red", 12);
        uriBuilder.matrixParam("frank");
        uriBuilder.path("blah");
        uriBuilder.matrixParam("another", "some;thi=ng");
        uriBuilder.matrixParam("color", "black");
        assertThat(uriBuilder.build().toString(), equalTo("/hello;color=red;color=12/blah;another=some%3Bthi%3Dng;color=black"));
    }

    @Test
    public void replaceMatrixParam() {
        UriBuilder uriBuilder = UriBuilder.fromPath("/hello");
        uriBuilder.matrixParam("color", "red");
        uriBuilder.path("blah");
        uriBuilder.matrixParam("color", "black", "solid", "1px");
        uriBuilder.replaceMatrixParam("color", "orange", "bright");
        assertThat(uriBuilder.build().toString(), equalTo("/hello;color=red/blah;color=orange;color=bright"));
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
            .replaceQueryParam("c", (Object[]) null)
            .replaceQueryParam("g", "h")
            .build();
        assertThat(uri.toString(), equalTo("http://example.org/blah?a=newA&e=f&g=h"));
    }

    @Test
    public void fragment() {
        URI uri = MuUriBuilder.fromUri(u("http://example.org"))
            .fragment("hello")
            .build();
        assertThat(uri.toString(), equalTo("http://example.org#hello"));
    }

    @Test
    public void resolveTemplateWithSlashEncoded() {
        UriBuilder uri = new MuUriBuilder()
            .uri("http://user:pw@example.org:12000/a/{name}/{ nameWithRegex : [0-9]+ }/{ name }?a=b#hi");
        uri.resolveTemplate("name", "a / name", true);
        assertThat(uri.build(1234).toString(),
            is("http://user:pw@example.org:12000/a/a%20%2F%20name/1234/a%20%2F%20name?a=b#hi"));
    }

    @Test
    public void resolveTemplateWithSlashNotEncoded() {
        UriBuilder uri = new MuUriBuilder()
            .uri("http://user:pw@example.org:12000/a/{name}/{ nameWithRegex : [0-9]+ }/{ name }?a=b#hi");
        uri.resolveTemplate("name", "a / name%25", false);
        assertThat(uri.build(1234).toString(),
            is("http://user:pw@example.org:12000/a/a%20/%20name%2525/1234/a%20/%20name%2525?a=b#hi"));
    }

    @Test
    public void resolveTemplateFromEncoded() {
        UriBuilder uri = new MuUriBuilder()
            .uri("http://user:pw@example.org:12000/a/{name}/{ nameWithRegex : [0-9]+ }/{ name }?a=b#hi");
        uri.resolveTemplateFromEncoded("name", "a / name%25");
        assertThat(uri.build(1234).toString(),
            is("http://user:pw@example.org:12000/a/a%20%2F%20name%25/1234/a%20%2F%20name%25?a=b#hi"));
    }

    @Test
    public void resolveTemplates() {
        UriBuilder uri = new MuUriBuilder()
            .uri("http://user:pw@example.org:12000/a/{name}/{ nameWithRegex : [0-9]+ }/{ name }?a=b#hi");
        Map<String, Object> map = new HashMap<>();
        map.put("name", "a / name");
        map.put("nameWithRegex", 1234);

        assertThat(uri.clone().resolveTemplates(map).build().toString(),
            equalTo(uri.clone().resolveTemplates(map, true).build().toString()));

        assertThat(uri.clone().resolveTemplates(map, true).build().toString(),
            is("http://user:pw@example.org:12000/a/a%20%2F%20name/1234/a%20%2F%20name?a=b#hi"));
        assertThat(uri.clone().resolveTemplates(map, false).build().toString(),
            is("http://user:pw@example.org:12000/a/a%20/%20name/1234/a%20/%20name?a=b#hi"));
    }

    @Test
    public void resolveTemplatesFromEncoded() {
        UriBuilder uri = new MuUriBuilder()
            .uri("http://user:pw@example.org:12000/a/{name}/{ nameWithRegex : [0-9]+ }/{ name }?a=b#hi");
        Map<String, Object> map = new HashMap<>();
        map.put("name", "a / name%25%");
        map.put("nameWithRegex", 1234);
        assertThat(uri.clone().resolveTemplatesFromEncoded(map).build(), Matchers.equalTo(uri.buildFromEncodedMap(map)));
    }

    @Test
    public void buildFromMap() {
        UriBuilder builder = MuUriBuilder.fromUri("http://localhost:8123/{class}/{method}?some=thing");
        Map<String, Object> map = new HashMap<>();
        map.put("class", "the Class");
        map.put("method", "the / method");
        assertThat(builder.buildFromMap(map),
            equalTo(builder.buildFromMap(map, true)));
        assertThat(builder.buildFromMap(map, true),
            equalTo(u("http://localhost:8123/the%20Class/the%20%2F%20method?some=thing")));
        assertThat(builder.buildFromMap(map, false),
            equalTo(u("http://localhost:8123/the%20Class/the%20/%20method?some=thing")));

    }

    @Test
    public void buildFromEncodedMap() {
        UriBuilder builder = MuUriBuilder.fromUri("http://localhost:8123/{class}/{method}?some=thing");
        Map<String, Object> map = new HashMap<>();
        map.put("class", "the%20Class");
        map.put("method", "the%20method");
        assertThat(builder.buildFromEncodedMap(map),
            equalTo(u("http://localhost:8123/the%20Class/the%20method?some=thing")));
    }

    @Test
    public void buildFromValues() {
        UriBuilder uri = new MuUriBuilder()
            .uri("http://user:pw@example.org:12000/a/{name}/{ nameWithRegex : [0-9]+ }/{ name }/?a=b#hi");
        Object[] values = {"a / name", 1234};
        assertThat(uri.build(values), equalTo(uri.build(values, true)));
        assertThat(uri.build(values, true).toString(),
            equalTo("http://user:pw@example.org:12000/a/a%20%2F%20name/1234/a%20%2F%20name/?a=b#hi"));
        assertThat(uri.build(values, false).toString(),
            equalTo("http://user:pw@example.org:12000/a/a%20/%20name/1234/a%20/%20name/?a=b#hi"));
    }

    @Test
    public void buildFromEncoded() {
        UriBuilder uri = new MuUriBuilder()
            .uri("http://user:pw@example.org:12000/a/{name}/{ nameWithRegex : [0-9]+ }/{ name }?a=b#hi");
        Object[] values = {"a%20%2F%20name", 1234};
        assertThat(uri.buildFromEncoded(values).toString(),
            equalTo("http://user:pw@example.org:12000/a/a%20%2F%20name/1234/a%20%2F%20name?a=b#hi"));
    }

    @Test
    public void toTemplate() {
        UriBuilder uri = new MuUriBuilder()
            .uri("http://user:pw@example.org:12000/a/{name}/{ nameWithRegex : [0-9]+ }/{ name }/?a=b#hi");
        assertThat(uri.toTemplate(), equalTo("http://user:pw@example.org:12000/a/{name}/{ nameWithRegex : [0-9]+ }/{ name }/?a=b#hi"));
    }

    private static URI u(String uri) {
        return URI.create(uri);
    }
}