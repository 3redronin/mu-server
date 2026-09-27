package io.muserver;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import io.muserver.handlers.HttpsRedirectorBuilder;
import io.muserver.rest.RestHandlerBuilder;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;
import scaffolding.Http1Client;
import scaffolding.MuAssert;

import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.muserver.MuServerBuilder.httpServer;
import static io.muserver.MuServerBuilder.muServer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

class Http1AbsoluteFormAuthorityTest {

    private @Nullable MuServer server;

    @Test
    void absoluteFormRequestTargetAuthorityTakesPrecedenceOverHost() throws Exception {
        server = httpServer()
            .addHandler(Method.GET, "/absolute-form-authority", (request, response, pathParams) -> {
                response.contentType(ContentTypes.TEXT_PLAIN_UTF8);
                response.write(request.uri().getRawAuthority() + "|" + request.headers().get(HeaderNames.HOST));
            })
            .start();

        String target = "http://trusted.example/absolute-form-authority";
        assertThat(requestBody(target, "attacker.example"), equalTo("trusted.example|trusted.example"));
        assertThat(requestBody(target, "trusted.example"), equalTo("trusted.example|trusted.example"));
    }

    @ParameterizedTest
    @CsvSource({
        "http://service_name/a%2Fb?x=%26, http://service_name/a%2Fb?x=%26",
        "http://foo%2Dbar:/a/../%7Euser?x=%2F, http://foo%2Dbar:/~user?x=%2F",
        "https://[::1]:8443/hello, https://[::1]:8443/hello",
        "https://example.com?x=1, https://example.com/?x=1"
    })
    void preservesTargetAuthorityAndNormalisesOnlyThePath(String target, String expected) throws Exception {
        server = httpServer().addHandler((request, response) -> {
            response.write(request.uri() + "|" + request.serverURI().getRawAuthority());
            return true;
        }).start();
        assertThat(requestBody(target, "other.example"), equalTo(expected + "|" + server.uri().getRawAuthority()));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "Forwarded: host=external.example;proto=https|https://external.example/hello",
        "Forwarded: for=192.0.2.1|http://trusted.example/hello",
        "X-Forwarded-Port: 8443|http://trusted.example:8443/hello"
    })
    void forwardingUsesTheEffectiveTargetHost(String forwarded, String expected) throws Exception {
        server = httpServer().addHandler((request, response) -> {
            response.write(request.uri() + "|" + request.headers().get(HeaderNames.HOST));
            return true;
        }).start();
        assertThat(requestBody("http://trusted.example/hello", "other.example", forwarded),
            equalTo(expected + "|trusted.example"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "http|http||http|false", "http|https||https|false",
        "https|http||http|true", "https|https||https|true",
        "http|http|Forwarded: proto=https|https|true",
        "https|https|Forwarded: proto=http|http|false",
        "http|http|X-Forwarded-Proto: https|https|true",
        "https|https|X-Forwarded-Proto: http|http|false",
        "https|http|Forwarded: for=192.0.2.1|http|true"
    })
    void securityUsesForwardingOrTransportInsteadOfTargetScheme(String transport, String targetScheme,
            @Nullable String forwarded, String expectedScheme, boolean secure) throws Exception {
        @Path("/security")
        class Resource {
            @GET public String get(@Context SecurityContext security, @Context UriInfo uri) {
                return uri.getRequestUri().getScheme() + "|" + security.isSecure();
            }
        }
        server = muServer().withHttpPort(0).withHttpsPort(0)
            .addHandler((request, response) -> {
                response.headers().set("X-Secure", request.isSecure());
                return false;
            })
            .addHandler(RestHandlerBuilder.restHandler(new Resource())).start();
        URI endpoint = transport.equals("https") ? server.httpsUri() : server.httpUri();
        try (var client = Http1Client.connect(endpoint)) {
            client.writeAscii("GET " + targetScheme + "://trusted.example/security HTTP/1.1\r\n")
                .writeHeader("Host", "other.example").writeHeader("Connection", "close");
            if (forwarded != null) client.writeAscii(forwarded + "\r\n");
            client.endHeaders().flush();
            assertThat(client.readLine(), startsWith("HTTP/1.1 200 "));
            Headers headers = client.readHeaders();
            assertThat(headers.get("X-Secure"), equalTo(Boolean.toString(secure)));
            assertThat(client.readBody(headers), equalTo(expectedScheme + "|" + secure));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/hello", "http://trusted.example/hello"})
    void http11RequiresHostBeforeTargetReplacement(String target) throws Exception {
        startAuthorityServer();
        assertThat(requestStatus(target), startsWith("HTTP/1.1 400 "));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/hello", "http://trusted.example/hello"})
    void http10CanOmitHost(String target) throws Exception {
        startAuthorityServer();
        try (var client = Http1Client.connect(server)) {
            client.writeAscii("GET " + target + " HTTP/1.0\r\nConnection: close\r\n\r\n").flush();
            assertThat(client.readLine(), startsWith("HTTP/1.1 200 "));
            assertThat(client.readBody(client.readHeaders()), equalTo(target.startsWith("http:")
                ? "trusted.example" : server.uri().getAuthority()));
        }
    }

    @ParameterizedTest
    @CsvSource({"POST,400", "UNKNOWN,405"})
    void rejectionDoesNotWaitForAnExpectedBody(String method, int status) throws Exception {
        AtomicBoolean dispatched = new AtomicBoolean();
        CompletableFuture<RejectedRequest> rejected = new CompletableFuture<>();
        server = httpServer().addRequestRejectListener(rejected::complete)
            .addHandler((request, response) -> { dispatched.set(true); return true; }).start();
        try (var socket = new Socket(server.uri().getHost(), server.uri().getPort())) {
            socket.setSoTimeout(3000);
            socket.getOutputStream().write((method + " http://trusted.example/ HTTP/1.1\r\n"
                + "Host: user@evil.example\r\nContent-Length: 20\r\nExpect: 100-continue\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
            assertThat(response, startsWith("HTTP/1.1 " + status + " "));
            assertThat(response.contains("100 Continue"), equalTo(false));
        }
        assertThat(dispatched.get(), equalTo(false));
        assertThat(rejected.get(3, TimeUnit.SECONDS).status(), equalTo(status));
    }

    @ParameterizedTest
    @CsvSource({"service_name:123,443,service_name", "foo%2Dbar:,8443,foo%2Dbar:8443",
        "[::1]:123,443,[::1]", "[::1],8443,[::1]:8443"})
    void redirectPreservesRawAuthorityPathAndQuery(String authority, int port, String expected) throws Exception {
        server = httpServer().addHandler(HttpsRedirectorBuilder.toHttpsPort(port)).start();
        String path = "/a%2Fb?x=%26&y=%252F";
        assertThat(requestLocation(server.httpUri(), "http://" + authority + path, "other.example"),
            equalTo("https://" + expected + path));
    }

    @ParameterizedTest
    @CsvSource({"http,http,301", "http,https,301", "https,http,200", "https,https,200"})
    void redirectsAndHstsFollowTransport(String transport, String scheme, int status) throws Exception {
        server = muServer().withHttpPort(0).withHttpsPort(0)
            .addHandler(HttpsRedirectorBuilder.toHttpsPort(443).withHSTSExpireTime(1, TimeUnit.DAYS))
            .addHandler((request, response) -> { response.write("secure"); return true; }).start();
        URI endpoint = transport.equals("https") ? server.httpsUri() : server.httpUri();
        try (var client = Http1Client.connect(endpoint)) {
            client.writeAscii("GET " + scheme + "://trusted.example/ HTTP/1.1\r\n")
                .writeHeader("Host", "other.example").writeHeader("Connection", "close").endHeaders().flush();
            assertThat(client.readLine(), startsWith("HTTP/1.1 " + status + " "));
            Headers headers = client.readHeaders();
            if (status == 301) {
                assertThat(headers.get("Location"), equalTo("https://trusted.example/"));
                assertThat(headers.get("Strict-Transport-Security"), equalTo(null));
            } else {
                assertThat(headers.get("Strict-Transport-Security"), equalTo("max-age=86400"));
                assertThat(client.readBody(headers), equalTo("secure"));
            }
        }
    }

    @Test
    void registeredNameHostWithUnderscoreIsAccepted() throws Exception {
        startAuthorityServer();
        assertThat(requestBody("/absolute-form-authority", "service_name"), equalTo("service_name"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"user@evil.example", "evil.example/path", "evil.example:abc"})
    void invalidHostAuthorityIsRejected(String host) throws Exception {
        startAuthorityServer();
        for (String target : new String[]{"/hello", "http://trusted.example/hello"}) {
            assertThat(requestStatus(target, "Host: " + host), startsWith("HTTP/1.1 400 "));
        }
    }

    @Test
    void invalidHostAuthorityIsRejectedEvenWithForwardedHeaders() throws Exception {
        startAuthorityServer();
        assertThat(requestStatus("http://trusted.example/absolute-form-authority",
            "Host: user@evil.example", "Forwarded: host=external.example;proto=https"),
            startsWith("HTTP/1.1 400 "));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/hello", "http://trusted.example/hello"})
    void duplicateHostFieldsAreRejected(String target) throws Exception {
        startAuthorityServer();
        assertThat(requestStatus(target, "Host: trusted.example", "Host: attacker.example"),
            startsWith("HTTP/1.1 400 "));
    }

    @Test
    void malformedAbsoluteTargetPortIsRejected() throws Exception {
        startAuthorityServer();
        assertThat(requestStatus("http://trusted.example:abc/absolute-form-authority", "Host: trusted.example"),
            startsWith("HTTP/1.1 400 "));
    }

    @Test
    void absoluteTargetFragmentIsRejected() throws Exception {
        startAuthorityServer();
        assertThat(requestStatus("http://trusted.example/absolute-form-authority#fragment", "Host: trusted.example"),
            startsWith("HTTP/1.1 400 "));
    }

    @Test
    void absoluteTargetUserInfoIsRejected() throws Exception {
        startAuthorityServer();
        assertThat(requestStatus("http://user@trusted.example/absolute-form-authority", "Host: trusted.example"),
            startsWith("HTTP/1.1 400 "));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http:/absolute-form-authority", "localhost:443"})
    void absoluteTargetWithoutAuthorityIsRejected(String target) throws Exception {
        startAuthorityServer();
        assertThat(requestStatus(target, "Host: trusted.example"),
            startsWith("HTTP/1.1 400 "));
    }

    private void startAuthorityServer() {
        server = httpServer().addHandler((request, response) -> {
            response.write(request.uri().getAuthority());
            return true;
        }).start();
    }

    private String requestBody(String target, String host, String... extraHeaders) throws Exception {
        try (var socket = new Socket("127.0.0.1", server.uri().getPort())) {
            socket.setSoTimeout(3000);
            var client = new Http1Client(socket, socket.getInputStream(), socket.getOutputStream(), server.uri());
            client.writeAscii("GET " + target + " HTTP/1.1\r\n")
                .writeHeader("Host", host);
            for (String header : extraHeaders) client.writeAscii(header + "\r\n");
            client.writeHeader("Connection", "close").endHeaders().flush();
            assertThat(client.readLine(), startsWith("HTTP/1.1 200 "));
            return client.readBody(client.readHeaders());
        }
    }

    private String requestStatus(String target, String... headers) throws Exception {
        try (var socket = new Socket("127.0.0.1", server.uri().getPort())) {
            socket.setSoTimeout(3000);
            var client = new Http1Client(socket, socket.getInputStream(), socket.getOutputStream(), server.uri());
            client.writeAscii("GET " + target + " HTTP/1.1\r\n");
            for (String header : headers) client.writeAscii(header + "\r\n");
            client.writeAscii("Connection: close\r\n\r\n").flush();
            return client.readLine();
        }
    }

    private String requestLocation(URI endpoint, String target, String host) throws Exception {
        try (var socket = new Socket(endpoint.getHost(), endpoint.getPort())) {
            socket.setSoTimeout(3000);
            var client = new Http1Client(socket, socket.getInputStream(), socket.getOutputStream(), endpoint);
            client.writeAscii("GET " + target + " HTTP/1.1\r\n")
                .writeHeader("Host", host)
                .writeHeader("Connection", "close")
                .endHeaders()
                .flush();
            assertThat(client.readLine(), startsWith("HTTP/1.1 301 "));
            return client.readHeaders().get("Location");
        }
    }

    @AfterEach
    void stopServer() {
        MuAssert.stopAndCheck(server);
    }
}
