package io.muserver.rest;

import io.muserver.Cookie;
import io.muserver.HeaderNames;
import io.muserver.Headers;
import org.junit.Test;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedHashMap;
import java.util.*;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class JaxRsHttpHeadersAdapterTest {
    static {
        MuRuntimeDelegate.ensureSet();
    }

    private final Headers reqHeaders = new Headers();
    private final Set<Cookie> cookies = new HashSet<>();
    private final JaxRsHttpHeadersAdapter httpHeaders = new JaxRsHttpHeadersAdapter(reqHeaders, cookies);


    @Test
    public void getRequestHeader() {
        reqHeaders.add("Now", "NoLight");
        reqHeaders.add("X-Blah", "something");
        assertThat(httpHeaders.getRequestHeader("X-Blah"), equalTo(singletonList("something")));
        assertThat(httpHeaders.getRequestHeader("X-Blah"), equalTo(httpHeaders.getRequestHeaders().get("X-Blah")));
    }

    @Test
    public void getHeaderString() {
        reqHeaders.add("Now", "NoLight");
        reqHeaders.add("NoValues", "");
        reqHeaders.add("X-Blah", "something");
        reqHeaders.add("X-Blah", "another-something");
        assertThat(httpHeaders.getHeaderString("NotThere"), is(nullValue()));
        assertThat(httpHeaders.getHeaderString("NoValues"), equalTo(""));
        assertThat(httpHeaders.getHeaderString("Now"), equalTo("NoLight"));
        assertThat(httpHeaders.getHeaderString("X-Blah"), equalTo("something,another-something"));
    }

    @Test
    public void getRequestHeaders() {
        reqHeaders.add("Now", "NoLight");
        reqHeaders.add("X-Blah", "something");

        MultivaluedHashMap<String,String> expected = new MultivaluedHashMap<>();
        for (Map.Entry<String, String> entry : reqHeaders) {
            expected.add(entry.getKey(), entry.getValue());
        }
        assertThat(httpHeaders.getRequestHeaders(), equalTo(expected));
    }

    @Test
    public void getAcceptableMediaTypes() {
        assertThat(httpHeaders.getAcceptableMediaTypes(), equalTo(Collections.singletonList(MediaType.WILDCARD_TYPE)));
        reqHeaders.add(HeaderNames.ACCEPT, "text/plain");
        reqHeaders.add(HeaderNames.ACCEPT, "application/json");
        assertThat(httpHeaders.getAcceptableMediaTypes(), equalTo(asList(MediaType.TEXT_PLAIN_TYPE, MediaType.APPLICATION_JSON_TYPE)));
    }

    @Test
    public void getAcceptableLanguages() {
        assertThat(httpHeaders.getAcceptableLanguages(), equalTo(Collections.singletonList(new Locale("*"))));
        reqHeaders.add(HeaderNames.ACCEPT_LANGUAGE, "en-GB,en-US;q=0.9,en;q=0.8");
        assertThat(httpHeaders.getAcceptableLanguages(), equalTo(asList(Locale.UK, Locale.US, Locale.ENGLISH)));
    }

    @Test
    public void getMediaType() {
        assertThat(httpHeaders.getMediaType(), is(nullValue()));
        reqHeaders.add(HeaderNames.CONTENT_TYPE, "application/json");
        assertThat(httpHeaders.getMediaType(), equalTo(MediaType.APPLICATION_JSON_TYPE));
    }

    @Test
    public void getLanguage() {
        assertThat(httpHeaders.getLanguage(), is(nullValue()));
        reqHeaders.add(HeaderNames.CONTENT_LANGUAGE, "en-GB");
        assertThat(httpHeaders.getLanguage(), equalTo(Locale.UK));
    }

    @Test
    public void getCookies() {
        cookies.add(new Cookie("Blah", "Hello"));
        cookies.add(new Cookie("Blah2", "Hello2"));
        Map<String, javax.ws.rs.core.Cookie> expected = new HashMap<>();
        expected.put("Blah", new javax.ws.rs.core.Cookie("Blah", "Hello"));
        expected.put("Blah2", new javax.ws.rs.core.Cookie("Blah2", "Hello2"));
        assertThat(httpHeaders.getCookies(), equalTo(expected));
    }

    @Test
    public void getDate() {
        assertThat(httpHeaders.getDate(), is(nullValue()));
        reqHeaders.set("Date", new Date(1519484802844L));
        assertThat(httpHeaders.getDate(), equalTo(new Date(1519484802000L)));
    }

    @Test
    public void getLength() {
        assertThat(httpHeaders.getLength(), is(-1));
        reqHeaders.set("Content-Length", 1234L);
        assertThat(httpHeaders.getLength(), is(1234));
    }

}