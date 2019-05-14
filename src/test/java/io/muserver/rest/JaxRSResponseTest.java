package io.muserver.rest;

import io.muserver.MuServer;
import io.muserver.Mutils;
import org.junit.Test;
import scaffolding.ServerUtils;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.core.*;
import java.net.URI;
import java.util.Date;
import java.util.Locale;

import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class JaxRSResponseTest {
    static {
        MuRuntimeDelegate.ensureSet();
    }

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

        MultivaluedMap<String, String> actual = response.getStringHeaders();
        assertThat(actual.get("allow"), contains("HEAD,GET"));
        assertThat(response.getAllowedMethods(), containsInAnyOrder("HEAD", "GET"));
        assertThat(actual.get("cache-control"), contains("private, no-transform, must-revalidate, max-age=10"));
        assertThat(response.getLength(), is(-1));
        assertThat(actual.get("content-location"), contains("http://localhost:8080"));
        assertThat(actual.get("content-encoding"), contains("UTF-8"));
        assertThat(actual.get("expires"), contains("Mon, 1 Jan 2018 02:24:12 GMT"));
        assertThat(actual.get("content-language"), contains("fr-CA"));
        assertThat(response.getLanguage(), equalTo(Locale.CANADA_FRENCH));
        assertThat(actual.get("last-modified"), contains("Mon, 1 Jan 2018 02:23:20 GMT"));
        assertThat(response.getLastModified(), equalTo(Mutils.fromHttpDate("Mon, 1 Jan 2018 02:23:20 GMT")));

        assertThat(actual.get("link"), contains("<http://www.example.org>; rel=\"meta\""));
        assertThat(response.hasLink("meta"), is(true));
        assertThat(response.hasLink("beta"), is(false));
        assertThat(response.getLink("meta"), equalTo(Link.valueOf("<http://www.example.org>; rel=\"meta\"")));
        assertThat(response.getLink("beta"), is(nullValue()));
        assertThat(response.getLinks(), containsInAnyOrder(Link.valueOf("<http://www.example.org>; rel=\"meta\"")));
        assertThat(response.getLinkBuilder("meta").title("I-built-this").build().toString(), equalTo("<http://www.example.org>; rel=\"meta\"; title=\"I-built-this\""));

        assertThat(actual.get("location"), contains("/some-location"));
        assertThat(response.getLocation(), equalTo(URI.create("/some-location")));
        assertThat(actual.get("etag"), contains("W/\"lkajsd\\\"fkljsklfdj\""));
        assertThat(response.getEntityTag().toString(), is("W/\"lkajsd\\\"fkljsklfdj\""));
//        assertThat(actual.get("Vary"), equalTo("???"));
        assertThat(actual.get("x-another"), contains("something"));
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

        MuServer server = ServerUtils.httpsServerForTest().addHandler(restHandler(new Blah())).start();
        try (okhttp3.Response resp = call(request().url(server.uri().resolve("/complex").toString()))) {
            assertThat(resp.code(), is(500));
            assertThat(resp.headers(HttpHeaders.CONTENT_TYPE), contains(equalTo("application/octet-stream")));
            assertThat(resp.headers(HttpHeaders.CACHE_CONTROL), contains(equalTo("no-store, max-age=1000, s-maxage=31536000")));
            assertThat(resp.headers(HttpHeaders.CONTENT_LOCATION), contains(equalTo("http://example.org")));
            assertThat(resp.headers(HttpHeaders.CONTENT_ENCODING), contains(equalTo("UTF-8")));
            assertThat(resp.headers(HttpHeaders.CONTENT_LANGUAGE), contains(equalTo("zh-CN")));
            assertThat(resp.headers(HttpHeaders.LAST_MODIFIED), contains(equalTo("Mon, 11 Feb 2019 15:58:18 GMT")));
            assertThat(resp.headers(HttpHeaders.EXPIRES), contains(equalTo("Tue, 12 Feb 2019 14:11:38 GMT")));
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

    @Test
    public void usesHeaderDelegatesIfAvailable() {
        Response resp = JaxRSResponse.ok()
            .header("cache", cacheControl())
            .header("string-val", "A string val")
            .header("int-val", 1234)
            .build();
        assertThat(resp.getHeaderString("cache"), is("private, no-transform, must-revalidate, max-age=10"));
        assertThat(resp.getHeaderString("string-val"), is("A string val"));
        assertThat(resp.getHeaderString("int-val"), is("1234"));
    }

    @Test
    public void getDateWorks() {
        assertThat(JaxRSResponse.ok().build().getDate(), is(nullValue()));
        Date now = new Date();
        assertThat(JaxRSResponse.ok().header("date", now).build().getDate(), is(now));
    }

    private static CacheControl cacheControl() {
        CacheControl cc = new CacheControl();
        cc.setMustRevalidate(true);
        cc.setPrivate(true);
        cc.setMaxAge(10);
        return cc;
    }

}