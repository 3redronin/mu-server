package io.muserver.rest;

import io.muserver.HeaderNames;
import io.muserver.Headers;
import io.muserver.MuServer;
import org.hamcrest.MatcherAssert;
import org.junit.Test;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.core.*;
import java.net.URI;
import java.util.Date;
import java.util.Locale;

import static io.muserver.MuServerBuilder.httpServer;
import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class JaxRSResponseTest {

    @Test
    public void headersCanBeGottenFromIt() {
        Response.ResponseBuilder builder = new JaxRSResponse.Builder()
            .allow("GET", "HEAD")
            .cacheControl(cacheControl())
            .contentLocation(URI.create("http://localhost:8080"))
            .cookie(new NewCookie("Some", "Value", "/here", "localhost", "no comment", 10, true))
            .encoding("UTF-8")
            .expires(new Date(1514773452217L))
            .language(Locale.CANADA_FRENCH)
            .lastModified(new Date(1514773400000L))
            .link(URI.create("http://www.example.org"), "meta")
            .location(URI.create("/some-location"))
            .status(201)
            .tag(new EntityTag("lkajsd\"fkljsklfdj", true))
            .variant(new Variant(MediaType.APPLICATION_JSON_TYPE, Locale.CHINESE, "UTF-8"))
            .header("X-Another", "something");

        JaxRSResponse response = (JaxRSResponse) builder.build();
        assertThat(response.getStatus(), is(201));

        Headers actual = response.getMuHeaders();
        MatcherAssert.assertThat(actual.get(HeaderNames.ALLOW), equalTo("HEAD,GET"));
        assertThat(actual.get(HeaderNames.CACHE_CONTROL), equalTo("private, no-transform, must-revalidate, max-age=10"));
        assertThat(actual.get(HeaderNames.CONTENT_LOCATION), equalTo("http://localhost:8080"));
        assertThat(actual.get(HeaderNames.CONTENT_ENCODING), equalTo("UTF-8"));
        assertThat(actual.get(HeaderNames.EXPIRES), equalTo("Mon, 1 Jan 2018 02:24:12 GMT"));
        assertThat(actual.get(HeaderNames.CONTENT_LANGUAGE), equalTo("fr-CA"));
        assertThat(actual.get(HeaderNames.LAST_MODIFIED), equalTo("Mon, 1 Jan 2018 02:23:20 GMT"));
        assertThat(actual.getAll(HeaderNames.LINK), contains(equalTo("<http://www.example.org>; rel=\"meta\"")));
        assertThat(actual.get(HeaderNames.LOCATION), equalTo("/some-location"));
        assertThat(actual.get(HeaderNames.ETAG), equalTo("W/\"lkajsd\\\"fkljsklfdj\""));
//        assertThat(actual.get(HeaderNames.VARY), equalTo("???"));
        assertThat(actual.get("X-Another"), equalTo("something"));
    }

    @Test
    public void complexResponsesWork() {
        @Path("/complex")
        class Blah {
            @GET
            public Response get() {
                return Response.ok()
                    .type(MediaType.APPLICATION_OCTET_STREAM_TYPE)
                    .cacheControl(CacheControl.valueOf("max-age=1000,s-maxage=31536000,no-store"))
                    .contentLocation(URI.create("http://example.org"))
                    .encoding("UTF-8")
                    .expires(new Date(1549980698731L))
                    .language(Locale.SIMPLIFIED_CHINESE)
                    .lastModified(new Date(1549900698731L))
                    .link(URI.create("http://example.org/contact"), "contact")
                    .link(URI.create("http://example.org/terms"), "terms")
                    .links(Link.fromUri("/readme").rel("readme").build())
                    .tag(EntityTag.valueOf("W/\"WEAKTAG\""))
                    .allow("GET", "GET", "HEAD")
                    .cookie(new NewCookie("token", "SLDKFJKLEWJRIOEWURIOD289374", "/complex", null, null, 10000, true, true))
                    .status(Response.Status.INTERNAL_SERVER_ERROR)
                    .header("arb", "bitrary")
                    .build();
            }
        }

        MuServer server = httpServer().addHandler(restHandler(new Blah())).start();
        try (okhttp3.Response resp = call(request().url(server.uri().resolve("/complex").toString()))) {
            assertThat(resp.code(), is(500));
            assertThat(resp.headers("content-type"), contains(equalTo("application/octet-stream")));
            assertThat(resp.headers("cache-control"), contains(equalTo("no-store, max-age=1000, s-maxage=31536000")));
            assertThat(resp.headers("content-location"), contains(equalTo("http://example.org")));
            assertThat(resp.headers("content-encoding"), contains(equalTo("UTF-8")));
            assertThat(resp.headers("content-language"), contains(equalTo("zh-CN")));
            assertThat(resp.headers("last-modified"), contains(equalTo("Mon, 11 Feb 2019 15:58:18 GMT")));
            assertThat(resp.headers("expires"), contains(equalTo("Tue, 12 Feb 2019 14:11:38 GMT")));
            assertThat(resp.headers("link"), containsInAnyOrder(
                equalTo("<http://example.org/contact>; rel=\"contact\""),
                equalTo("<http://example.org/terms>; rel=\"terms\""),
                equalTo("</readme>; rel=\"readme\"")
            ));
            assertThat(resp.headers("etag"), contains(equalTo("W/\"WEAKTAG\"")));
            assertThat(resp.headers("allow"), contains(equalTo("HEAD,GET")));
            assertThat(resp.headers("set-cookie"), contains(containsString("token=SLDKFJKLEWJRIOEWURIOD289374; Max-Age=10000; Expires=")));
            assertThat(resp.headers("set-cookie"), contains(containsString("Path=/complex; Secure; HTTPOnly")));
            assertThat(resp.headers("arb"), contains(equalTo("bitrary")));
        } finally {
            server.stop();
        }
    }

    private static CacheControl cacheControl() {
        CacheControl cc = new CacheControl();
        cc.setMustRevalidate(true);
        cc.setPrivate(true);
        cc.setMaxAge(10);
        return cc;
    }

}