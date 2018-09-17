package io.muserver;

import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import scaffolding.ClientUtils;
import scaffolding.MuAssert;

import javax.ws.rs.CookieParam;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.core.NewCookie;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static io.muserver.MuServerBuilder.httpServer;
import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.request;

public class CookieTest {

    private MuServer server;
    private OkHttpClient client;

    @Before
    public void setupClient() {
        CookieJar inMemoryCookieJar = new InMemoryCookieJar();
        client = ClientUtils.client.newBuilder()
            .cookieJar(inMemoryCookieJar)
            .build();
    }

    @Test
    public void canSetThemFromTheServer() throws IOException {
        server = httpServer()
            .addHandler((request, response) -> {
                Cookie cookie = Cookie.secureCookie("Session", "Some value");
                response.addCookie(cookie);
                return true;
            }).start();

        client.newCall(request().url(serverUrl()).build()).execute();
        List<okhttp3.Cookie> cookies = getCookies();

        assertThat(cookies, hasSize(1));
        okhttp3.Cookie actual = cookies.get(0);
        assertThat(actual.name(), equalTo("Session"));
        assertThat(actual.value(), equalTo("Some value"));
        assertThat(actual.domain(), equalTo("localhost"));
        assertThat(actual.hostOnly(), equalTo(true));
        assertThat(actual.persistent(), equalTo(false));
        assertThat(actual.httpOnly(), equalTo(true));
        assertThat(actual.secure(), equalTo(true));
    }

    @Test
    public void cookiesAreAvailableInJaxRS() throws IOException {

        @Path("biscuits")
        class Biscuits {
            @GET
            @Path("set")
            public javax.ws.rs.core.Response setCookie() {
                return javax.ws.rs.core.Response.noContent().cookie(new NewCookie("Something", Mutils.urlEncode("This is a cookie value"))).build();
            }

            @GET
            @Path("get")
            public String getCookieValue(@CookieParam("Something") String cookieValue) {
                return cookieValue;
            }
        }

        server = httpServer()
            .addHandler(restHandler(new Biscuits())).start();

        try (Response setResp = client.newCall(request().url(server.uri().resolve("/biscuits/set").toString()).build()).execute()) {
            assertThat(setResp.code(), equalTo(204));
        }
        try (Response getResp = client.newCall(request().url(server.uri().resolve("/biscuits/get").toString()).build()).execute()) {
            assertThat(getResp.code(), equalTo(200));
            assertThat(getResp.body().string(), equalTo("This%20is%20a%20cookie%20value"));
        }
    }

    @Test
    public void multipleCookiesCanBeSetAndAreAllSentBackToTheServer() throws IOException {
        Set<Cookie> actualSentCookies = new HashSet<>();
        AtomicReference<Optional<String>> sessionLookup = new AtomicReference<>();
        AtomicReference<Optional<String>> nonExistentCookieLookup = new AtomicReference<>();
        server = httpServer()
            .addHandler(Method.GET, "/set", (request, response, pathParams) -> {
                Cookie cookie = Cookie.secureCookie("ASession", "SomeValue");
                Cookie cookie2 = new Cookie("Another", "Blah");
                response.addCookie(cookie);
                response.addCookie(cookie2);
            })
            .addHandler(Method.GET, "/save", (request, response, pathParams) -> {
                nonExistentCookieLookup.set(request.cookie("ThereIsNoCookie"));
                sessionLookup.set(request.cookie("ASession"));
                actualSentCookies.addAll(request.cookies());
            })
            .start();

        client.newCall(request().url(serverUrl().resolve("/set")).build()).execute();
        List<okhttp3.Cookie> cookies = getCookies();

        assertThat(cookies, hasSize(2));

        client.newCall(request().url(serverUrl().resolve("/save")).build()).execute();
        assertThat(actualSentCookies, hasSize(2));

        assertThat(nonExistentCookieLookup.get().isPresent(), is(false));
        assertThat(sessionLookup.get().isPresent(), is(true));
        assertThat(sessionLookup.get().get(), is("SomeValue"));

    }

    @Test
    public void cookieValuesAreNotUrlEncoded() throws IOException {
        server = httpServer()
            .addHandler((request, response) -> {
                response.addCookie(Cookie.secureCookie("A thing", "Some value & another thing=umm"));
                return true;
            }).start();

        client.newCall(request().url(serverUrl()).build()).execute();
        List<okhttp3.Cookie> cookies = getCookies();

        assertThat(cookies, hasSize(1));
        okhttp3.Cookie actual = cookies.get(0);
        assertThat(actual.name(), equalTo("A thing"));
        assertThat(actual.value(), equalTo("Some value & another thing=umm"));

    }

    private List<okhttp3.Cookie> getCookies() {
        return client.cookieJar().loadForRequest(serverUrl());
    }

    private HttpUrl serverUrl() {
        return HttpUrl.get(server.httpUri());
    }

    @After
    public void stopIt() {
        MuAssert.stopAndCheck(server);
    }

    private static class InMemoryCookieJar implements CookieJar {
        private final HashMap<String, List<okhttp3.Cookie>> cookieStore = new HashMap<>();

        @Override
        public void saveFromResponse(HttpUrl url, List<okhttp3.Cookie> cookies) {
            cookieStore.put(url.host(), cookies);
        }

        @Override
        public List<okhttp3.Cookie> loadForRequest(HttpUrl url) {
            List<okhttp3.Cookie> cookies = cookieStore.get(url.host());
            return cookies != null ? cookies : new ArrayList<>();
        }
    }
}
