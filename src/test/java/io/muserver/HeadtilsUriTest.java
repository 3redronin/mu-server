package io.muserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HeadtilsUriTest {
    @ParameterizedTest
    @CsvSource({
        "http://localhost:12345, localhost:12345, /hello, http://localhost:12345/hello",
        "http://127.0.0.1:12345, 127.0.0.1:12345, /hello?value=a%2Fb, http://127.0.0.1:12345/hello?value=a%2Fb",
        "https://localhost:443, localhost:443, /a%20b?x=one&x=two, https://localhost:443/a%20b?x=one&x=two",
        "http://[::1]:12345, [::1]:12345, /hello?value=%E2%82%AC, http://[::1]:12345/hello?value=%E2%82%AC",
        "http://localhost:12345, localhost:12345, /a/../hello, http://localhost:12345/a/../hello",
        "http://localhost:12345, localhost:12345, /, http://localhost:12345/",
        "http://localhost:12345, localhost, /hello, http://localhost:12345/hello",
        "http://localhost:12345, LOCALHOST:12345, /hello, http://LOCALHOST:12345/hello",
        "http://localhost:12345, example.com:54321, /hello?x=1, http://example.com:54321/hello?x=1"
    })
    void reconstructsRequestUriWithoutLosingAuthorityPathOrQuery(String origin, String host, String target, String expected) {
        URI defaultUri = URI.create(origin).resolve(target);
        URI actual = Headtils.getUri(LoggerFactory.getLogger(getClass()),
            Headers.create().set("Host", host), target, defaultUri);
        assertEquals(expected, actual.toString());
    }


    @ParameterizedTest
    @CsvSource({
        "Host, bad<host>:1234",
        "X-Forwarded-Host, bad<host>:1234",
        "Forwarded, host=bad<host>:1234"
    })
    void invalidAuthorityIsRejected(String header, String value) {
        Headers headers = Headers.create().set("Host", "localhost:12345").set(header, value);
        HttpException ex = assertThrows(HttpException.class, () -> Headtils.getUri(LoggerFactory.getLogger(getClass()),
            headers, "/hello", URI.create("http://localhost:12345/hello")));
        assertEquals(400, ex.status().code());
    }

    @Test
    void forwardedAuthorityAndSchemeStillOverrideAMatchingHost() {
        URI uri = Headtils.getUri(LoggerFactory.getLogger(getClass()), Headers.create()
            .set("Host", "localhost:12345")
            .set("Forwarded", "host=external.example;proto=https"),
            "/hello?x=1", URI.create("http://localhost:12345/hello?x=1"));
        assertEquals(URI.create("https://external.example/hello?x=1"), uri);
    }

    @Test
    void absoluteFormAuthorityAndSchemeOverrideHost() {
        URI uri = Headtils.getUri(LoggerFactory.getLogger(getClass()), Headers.create()
            .set("Host", "attacker.example"),
            "/hello?x=1", URI.create("http://localhost:12345/hello?x=1"),
            "https://trusted.example:8443/hello?x=1");
        assertEquals(URI.create("https://trusted.example:8443/hello?x=1"), uri);
    }

    @Test
    void forwardedAuthorityStillOverridesAbsoluteFormAuthority() {
        URI uri = Headtils.getUri(LoggerFactory.getLogger(getClass()), Headers.create()
            .set("Host", "attacker.example")
            .set("Forwarded", "host=external.example;proto=https"),
            "/hello?x=1", URI.create("http://localhost:12345/hello?x=1"),
            "http://trusted.example/hello?x=1");
        assertEquals(URI.create("https://external.example/hello?x=1"), uri);
    }

    @Test
    void forwardedPortWithoutForwardedHostUsesAbsoluteFormAuthority() {
        URI uri = Headtils.getUri(LoggerFactory.getLogger(getClass()), Headers.create()
            .set("Host", "attacker.example")
            .set("X-Forwarded-Port", "8443"),
            "/hello", URI.create("http://localhost:12345/hello"),
            "http://trusted.example/hello");
        assertEquals(URI.create("http://trusted.example:8443/hello"), uri);
    }

    @Test
    void absoluteFormAuthorityExcludesUserInfo() {
        URI uri = Headtils.getUri(LoggerFactory.getLogger(getClass()), Headers.create()
            .set("Host", "attacker.example"),
            "/hello", URI.create("http://localhost:12345/hello"),
            "http://user:password@trusted.example/hello");
        assertEquals(URI.create("http://trusted.example/hello"), uri);
    }

    @Test
    void invalidAbsoluteFormTargetAuthorityIsRejected() {
        HttpException ex = assertThrows(HttpException.class, () -> Headtils.getUri(LoggerFactory.getLogger(getClass()),
            Headers.create().set("Host", "attacker.example"), "/hello",
            URI.create("http://localhost:12345/hello"), "http://bad^host/hello"));
        assertEquals(400, ex.status().code());
    }

    @Test
    void invalidHostIsStillRejectedWithAbsoluteFormTarget() {
        HttpException ex = assertThrows(HttpException.class, () -> Headtils.getUri(LoggerFactory.getLogger(getClass()),
            Headers.create().set("Host", "bad<host>"), "/hello",
            URI.create("http://localhost:12345/hello"), "http://trusted.example/hello"));
        assertEquals(400, ex.status().code());
    }

    @Test
    void legacyForwardedAuthorityStillOverridesAMatchingHost() {
        URI uri = Headtils.getUri(LoggerFactory.getLogger(getClass()), Headers.create()
            .set("Host", "localhost:12345")
            .set("X-Forwarded-Host", "external.example:8443")
            .set("X-Forwarded-Proto", "https"),
            "/hello?x=1", URI.create("http://localhost:12345/hello?x=1"));
        assertEquals(URI.create("https://external.example:8443/hello?x=1"), uri);
    }
}
