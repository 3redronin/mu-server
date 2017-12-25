package ronin.muserver;

import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import scaffolding.ClientUtils;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static ronin.muserver.Method.GET;
import static ronin.muserver.MuServerBuilder.httpServer;
import static scaffolding.ClientUtils.request;

public class CookieTest {

    private MuServer server;
    private OkHttpClient client;

    @Before
    public void setupClient() {
        CookieJar inMemoryCookieJar = new InMemoryCookieJar();
        client = ClientUtils.newClient()
            .cookieJar(inMemoryCookieJar)
            .build();
    }

    @Test
    public void canSetThemFromTheServer() throws IOException {
        server = httpServer()
            .addHandler((request, response) -> {
                Cookie cookie = Cookie.secureCookie("Session", "Somevalue");
                response.addCookie(cookie);
                return true;
            }).start();

        client.newCall(request().url(serverUrl()).build()).execute();
        List<okhttp3.Cookie> cookies = getCookies();

        assertThat(cookies, hasSize(1));
        okhttp3.Cookie actual = cookies.get(0);
        assertThat(actual.name(), equalTo("Session"));
        assertThat(actual.value(), equalTo("Somevalue"));
        assertThat(actual.domain(), equalTo("localhost"));
        assertThat(actual.hostOnly(), equalTo(true));
        assertThat(actual.persistent(), equalTo(false));
        assertThat(actual.httpOnly(), equalTo(true));
        assertThat(actual.secure(), equalTo(true));
    }

    @Test
    public void multipleCookiesCanBeSetAndAreAllSentBackToTheServer() throws IOException {
        Set<Cookie> actualSentCookies = new HashSet<>();
        AtomicReference<Optional<Cookie>> sessionLookup = new AtomicReference<>();
        AtomicReference<Optional<Cookie>> nonExistentCookieLookup = new AtomicReference<>();
        server = httpServer()
            .addHandler(GET, "/set", (request, response) -> {
                Cookie cookie = Cookie.secureCookie("A Session", "Some value");
                Cookie cookie2 = new Cookie("Another", "Blah");
                response.addCookie(cookie);
                response.addCookie(cookie2);
                return true;
            })
            .addHandler(GET, "/save", (request, response) -> {
                nonExistentCookieLookup.set(request.cookie("ThereIsNoCookie"));
                sessionLookup.set(request.cookie("A Session"));
                actualSentCookies.addAll(request.cookies());
                return true;
            })
            .start();

        client.newCall(request().url(serverUrl().resolve("/set")).build()).execute();
        List<okhttp3.Cookie> cookies = getCookies();

        assertThat(cookies, hasSize(2));

        client.newCall(request().url(serverUrl().resolve("/save")).build()).execute();
        assertThat(actualSentCookies, hasSize(2));

        assertThat(nonExistentCookieLookup.get().isPresent(), is(false));
        assertThat(sessionLookup.get().isPresent(), is(true));
        assertThat(sessionLookup.get().get().value(), is("Some value"));

    }

    @Test
    public void cookieValuesAreUrlEncoded() throws IOException {
        server = httpServer()
            .addHandler((request, response) -> {
                response.addCookie(Cookie.secureCookie("A thing", "Some value & another thing=umm"));
                return true;
            }).start();

        client.newCall(request().url(serverUrl()).build()).execute();
        List<okhttp3.Cookie> cookies = getCookies();

        assertThat(cookies, hasSize(1));
        okhttp3.Cookie actual = cookies.get(0);
        assertThat(actual.name(), equalTo("A+thing"));
        assertThat(actual.value(), equalTo("Some+value+%26+another+thing%3Dumm"));

    }

    private List<okhttp3.Cookie> getCookies() {
        return client.cookieJar().loadForRequest(serverUrl());
    }

    private HttpUrl serverUrl() {
        return HttpUrl.get(server.httpUri());
    }

    @After
    public void stopIt() {
        server.stop();
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
