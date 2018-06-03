package io.muserver.rest;

import io.muserver.*;
import io.netty.handler.codec.http.HttpHeaderNames;
import org.junit.Test;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedHashMap;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
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
    private final JaxRsHttpHeadersAdapter httpHeaders = new JaxRsHttpHeadersAdapter(createRequest());
    private final Set<Cookie> cookies = new HashSet<>();


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


    private MuRequest createRequest() {
        return new MuRequest() {

            @Override
            public String contentType() {
                return reqHeaders.get(HttpHeaderNames.CONTENT_TYPE);
            }

            @Override
            public Method method() {
                return Method.POST;
            }

            @Override
            public URI uri() {
                return URI.create("http://localhost:123/some-path");
            }

            @Override
            public URI serverURI() {
                return uri();
            }

            @Override
            public Headers headers() {
                return reqHeaders;
            }

            @Override
            public Optional<InputStream> inputStream() {
                throw new NotImplementedException("mock");
            }

            @Override
            public String readBodyAsString() throws IOException {
                throw new NotImplementedException("mock");
            }

            @Override
            public List<UploadedFile> uploadedFiles(String name) {
                throw new NotImplementedException("mock");
            }

            @Override
            public UploadedFile uploadedFile(String name) {
                throw new NotImplementedException("mock");
            }

            @Override
            public String parameter(String name) {
                return null;
            }

            @Override
            public RequestParameters query() {
                throw new NotImplementedException("mock");
            }

            @Override
            public RequestParameters form() throws IOException {
                throw new NotImplementedException("mock");
            }

            @Override
            public List<String> parameters(String name) {
                return null;
            }

            @Override
            public String formValue(String name) throws IOException {
                return null;
            }

            @Override
            public List<String> formValues(String name) throws IOException {
                return null;
            }

            @Override
            public Set<Cookie> cookies() {
                return cookies;
            }

            @Override
            public Optional<String> cookie(String name) {
                return Optional.empty();
            }

            @Override
            public String contextPath() {
                return null;
            }

            @Override
            public String relativePath() {
                return null;
            }

            @Override
            public Object state() {
                throw new NotImplementedException();
            }

            @Override
            public void state(Object value) {
                throw new NotImplementedException();
            }
        };
    }

}