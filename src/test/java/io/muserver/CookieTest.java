package io.muserver;

import io.muserver.rest.MuRuntimeDelegate;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import scaffolding.ClientUtils;
import scaffolding.MuAssert;
import scaffolding.RawClient;
import scaffolding.ServerUtils;

import javax.ws.rs.CookieParam;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.core.NewCookie;
import javax.ws.rs.ext.RuntimeDelegate;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.request;
import static scaffolding.MuAssert.assertEventually;

public class CookieTest {

    private MuServer server;
    private OkHttpClient client;

    @BeforeEach
    public void setupClient() {
        CookieJar inMemoryCookieJar = new InMemoryCookieJar();
        client = ClientUtils.client.newBuilder()
            .cookieJar(inMemoryCookieJar)
            .build();
    }

    @Test
    public void canSetThemFromTheServer() throws IOException {
        server = ServerUtils.httpsServerForTest()
            .addHandler((request, response) -> {
                Cookie cookie = CookieBuilder.newSecureCookie()
                    .withName("Session")
                    .withValue("Some-value")
                    .build();
                response.addCookie(cookie);
                return true;
            }).start();

        client.newCall(request().url(serverUrl()).build()).execute().close();
        List<okhttp3.Cookie> cookies = getCookies();

        assertThat(cookies, hasSize(1));
        okhttp3.Cookie actual = cookies.get(0);
        assertThat(actual.name(), equalTo("Session"));
        assertThat(actual.value(), equalTo("Some-value"));
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
                NewCookie cookie = new NewCookie("Something", "123456", "/", null, "some comment", 360, false);
                return javax.ws.rs.core.Response.noContent().cookie(cookie).build();
            }

            @GET
            @Path("getString")
            public String getCookieStringValue(@CookieParam("Something") String cookieValue) {
                return cookieValue;
            }

            @GET
            @Path("getInt")
            public int getCookieIntValue(@CookieParam("Something") int cookieValue) {
                return cookieValue;
            }

            @GET
            @Path("getCookie")
            public String getCookie(@CookieParam("Something") javax.ws.rs.core.Cookie cookie) {
                return cookie.getName() + "=" + cookie.getValue();
            }

            @GET
            @Path("nullCookie")
            public String getNullCookie(@CookieParam("SomethingElse") javax.ws.rs.core.Cookie cookie) {
                return "cookie=" + cookie;
            }

            @GET
            @Path("getMuCookie")
            public String getCookie(@CookieParam("Something") Cookie cookie) {
                return cookie.name() + "=" + cookie.value();
            }

            @GET
            @Path("nullMuCookie")
            public String getNullCookie(@CookieParam("SomethingElse") Cookie cookie) {
                return "cookie=" + cookie;
            }
        }

        server = ServerUtils.httpsServerForTest()
            .addHandler(restHandler(new Biscuits())).start();

        try (Response setResp = client.newCall(request().url(server.uri().resolve("/biscuits/set").toString()).build()).execute()) {
            assertThat(setResp.code(), equalTo(204));
            String setCookie = setResp.headers().get("set-cookie");
            RuntimeDelegate.HeaderDelegate<NewCookie> headerDelegate = MuRuntimeDelegate.getInstance().createHeaderDelegate(NewCookie.class);
            NewCookie newCookie = headerDelegate.fromString(setCookie);
            assertThat(newCookie.getMaxAge(), equalTo(360));
            assertThat(newCookie.getPath(), equalTo("/"));
        }
        try (Response getResp = client.newCall(request().url(server.uri().resolve("/biscuits/getString").toString()).build()).execute()) {
            assertThat(getResp.code(), equalTo(200));
            assertThat(getResp.body().string(), equalTo("123456"));
        }
        try (Response getResp = client.newCall(request().url(server.uri().resolve("/biscuits/getInt").toString()).build()).execute()) {
            assertThat(getResp.body().string(), equalTo("123456"));
        }
        try (Response getResp = client.newCall(request().url(server.uri().resolve("/biscuits/getCookie").toString()).build()).execute()) {
            assertThat(getResp.body().string(), equalTo("Something=123456"));
        }
        try (Response getResp = client.newCall(request().url(server.uri().resolve("/biscuits/nullCookie").toString()).build()).execute()) {
            assertThat(getResp.body().string(), equalTo("cookie=null"));
        }
        try (Response getResp = client.newCall(request().url(server.uri().resolve("/biscuits/getMuCookie").toString()).build()).execute()) {
            assertThat(getResp.body().string(), equalTo("Something=123456"));
        }
        try (Response getResp = client.newCall(request().url(server.uri().resolve("/biscuits/nullMuCookie").toString()).build()).execute()) {
            assertThat(getResp.body().string(), equalTo("cookie=null"));
        }
    }

    @Test
    public void multipleCookiesCanBeSetAndAreAllSentBackToTheServer() throws IOException {
        Set<Cookie> actualSentCookies = new HashSet<>();
        AtomicReference<Optional<String>> sessionLookup = new AtomicReference<>();
        AtomicReference<Optional<String>> nonExistentCookieLookup = new AtomicReference<>();
        server = ServerUtils.httpsServerForTest()
            .addHandler(Method.GET, "/set", (request, response, pathParams) -> {
                Cookie cookie = CookieBuilder.newSecureCookie()
                    .withName("ASession")
                    .withValue("SomeValue")
                    .build();
                Cookie cookie2 = CookieBuilder.newCookie().withName("Another").withValue("Blah").build();
                response.addCookie(cookie);
                response.addCookie(cookie2);
            })
            .addHandler(Method.GET, "/save", (request, response, pathParams) -> {
                nonExistentCookieLookup.set(request.cookie("ThereIsNoCookie"));
                sessionLookup.set(request.cookie("ASession"));
                actualSentCookies.addAll(request.cookies());
            })
            .start();

        client.newCall(request().url(serverUrl().resolve("/set")).build()).execute().close();
        List<okhttp3.Cookie> cookies = getCookies();

        assertThat(cookies, hasSize(2));

        client.newCall(request().url(serverUrl().resolve("/save")).build()).execute().close();
        assertThat(actualSentCookies, hasSize(2));

        assertThat(nonExistentCookieLookup.get().isPresent(), is(false));
        assertThat(sessionLookup.get().isPresent(), is(true));
        assertThat(sessionLookup.get().get(), is("SomeValue"));
    }

    @Test
    public void ifCookiesAreSentAsSeparateHeadersItWorks() throws IOException {
        server = MuServerBuilder.httpServer()
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.addCookie(CookieBuilder.newSecureCookie().withName("hi").withValue("bal").build());
                response.write("START; " + request.cookies().stream().map(Cookie::toString)
                    .collect(Collectors.joining("; "))
                + "; END");
            })
            .start();

        try (RawClient rawClient = RawClient.create(server.uri())
            .sendStartLine("GET", "/")
            .sendHeader("host", server.uri().getAuthority())
            .sendHeader("cookie", "cookie1=something")
            .sendHeader("cookie", "cookie2=somethingelse")
            .endHeaders()
            .flushRequest()) {
            assertEventually(rawClient::responseString, endsWith("END"));
            assertThat(rawClient.responseString(), containsString("set-cookie: hi=bal; SameSite=Strict; Secure; HttpOnly"));
            assertThat(rawClient.responseString(), endsWith("START; cookie1=something; cookie2=somethingelse; END"));
        }
    }

    @Test
    public void cookieValuesCanBeUrlEncoded() throws IOException {
        server = ServerUtils.httpsServerForTest()
            .addHandler((request, response) -> {
                response.addCookie(CookieBuilder.newSecureCookie()
                    .withName("A-thing")
                    .withUrlEncodedValue("Some value & another thing=umm")
                    .build());
                return true;
            }).start();

        client.newCall(request().url(serverUrl()).build()).execute().close();
        List<okhttp3.Cookie> cookies = getCookies();

        assertThat(cookies, hasSize(1));
        okhttp3.Cookie actual = cookies.get(0);
        assertThat(actual.name(), equalTo("A-thing"));
        assertThat(actual.value(), equalTo("Some%20value%20%26%20another%20thing%3Dumm"));
    }

    @Test
    public void noCookiesReturnsEmptySet() throws IOException {
        server = ServerUtils.httpsServerForTest()
            .addHandler((request, response) -> {
                response.write(String.valueOf(request.cookies().isEmpty()));
                return true;
            }).start();

        client.newCall(request().url(serverUrl()).build()).execute().close();
        List<okhttp3.Cookie> cookies = getCookies();

        assertThat(cookies, hasSize(0));
    }

    private List<okhttp3.Cookie> getCookies() {
        return client.cookieJar().loadForRequest(serverUrl());
    }

    private HttpUrl serverUrl() {
        return HttpUrl.get(server.uri());
    }

    @AfterEach
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
