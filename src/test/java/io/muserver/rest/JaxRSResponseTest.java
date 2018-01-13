package io.muserver.rest;

import io.muserver.HeaderNames;
import io.muserver.Headers;
import org.hamcrest.MatcherAssert;
import org.junit.Test;

import javax.ws.rs.core.*;
import java.net.URI;
import java.util.Date;
import java.util.Locale;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

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
//            .link(URI.create("http://www.example.org"), "meta")
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
//        assertThat(actual.getAll(HeaderNames.LINK), contains("<http://www.example.org>;rel=meta"));
        assertThat(actual.get(HeaderNames.LOCATION), equalTo("/some-location"));
        assertThat(actual.get(HeaderNames.ETAG), equalTo("W/\"lkajsd\\\"fkljsklfdj\""));
//        assertThat(actual.get(HeaderNames.VARY), equalTo("???"));
        assertThat(actual.get("X-Another"), equalTo("something"));

        // untested: links(Link... links)
    }

    private static CacheControl cacheControl() {
        CacheControl cc = new CacheControl();
        cc.setMustRevalidate(true);
        cc.setPrivate(true);
        cc.setMaxAge(10);
        return cc;
    }

}