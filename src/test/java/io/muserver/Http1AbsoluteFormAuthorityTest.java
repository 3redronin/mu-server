package io.muserver;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
                response.write(request.uri().getAuthority());
            })
            .start();

        String target = "http://trusted.example/absolute-form-authority";
        assertThat(requestAuthority(target, "attacker.example"), equalTo("trusted.example"));
        assertThat(requestAuthority(target, "trusted.example"), equalTo("trusted.example"));
    }

    @Test
    void plaintextAbsoluteHttpsTargetStillRedirectsAsPlainHttp() throws Exception {
        server = muServer()
            .withHttpPort(0)
            .withHttpsPort(0)
            .addHandler((request, response) -> HttpsRedirectorBuilder
                .toHttpsPort(server.httpsUri().getPort()).build().handle(request, response))
            .addHandler((request, response) -> {
                response.write("not redirected");
                return true;
            })
            .start();

        String target = "https://trusted.example/absolute-form-authority";
        assertThat(requestLocation(server.httpUri(), target, "attacker.example"),
            equalTo("https://trusted.example:" + server.httpsUri().getPort() + "/absolute-form-authority"));
    }

    @Test
    void redirectPreservesRegisteredNameAuthority() throws Exception {
        server = muServer()
            .withHttpPort(0)
            .withHttpsPort(0)
            .addHandler((request, response) -> HttpsRedirectorBuilder
                .toHttpsPort(server.httpsUri().getPort()).build().handle(request, response))
            .start();

        assertThat(requestLocation(server.httpUri(), "http://service_name/absolute-form-authority", "service_name"),
            equalTo("https://service_name:" + server.httpsUri().getPort() + "/absolute-form-authority"));
    }

    @Test
    void plaintextAbsoluteHttpsTargetDoesNotMakeSecurityContextSecure() throws Exception {
        @Path("/absolute-form-authority")
        class SecureResource {
            @GET
            public String get(@Context SecurityContext securityContext, @Context UriInfo uriInfo) {
                return uriInfo.getRequestUri().getScheme() + "|" + securityContext.isSecure();
            }
        }
        server = httpServer()
            .addHandler(RestHandlerBuilder.restHandler(new SecureResource()))
            .start();

        assertThat(requestBody("https://trusted.example/absolute-form-authority", "trusted.example"), equalTo("https|false"));
    }

    @Test
    void forwardedHttpsSchemeStillMarksPlainTransportSecure() throws Exception {
        @Path("/absolute-form-authority")
        class SecureResource {
            @GET
            public String get(@Context SecurityContext securityContext, @Context UriInfo uriInfo) {
                return uriInfo.getRequestUri().getScheme() + "|" + securityContext.isSecure();
            }
        }
        server = httpServer()
            .addHandler(RestHandlerBuilder.restHandler(new SecureResource()))
            .start();

        assertThat(requestBody("http://trusted.example/absolute-form-authority", "trusted.example",
            "Forwarded: proto=https"), equalTo("https|true"));
    }

    @Test
    void registeredNameHostWithUnderscoreIsAccepted() throws Exception {
        startAuthorityServer();
        assertThat(requestBody("/absolute-form-authority", "service_name"), equalTo("service_name"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"user@evil.example", "evil.example/path", "evil.example:abc"})
    void invalidHostAuthorityIsRejectedForAbsoluteTarget(String host) throws Exception {
        startAuthorityServer();
        assertThat(requestStatus("http://trusted.example/absolute-form-authority", "Host: " + host),
            startsWith("HTTP/1.1 400 "));
    }

    @Test
    void invalidHostAuthorityIsRejectedEvenWithForwardedHeaders() throws Exception {
        startAuthorityServer();
        assertThat(requestStatus("http://trusted.example/absolute-form-authority",
            "Host: user@evil.example", "Forwarded: host=external.example;proto=https"),
            startsWith("HTTP/1.1 400 "));
    }

    @Test
    void duplicateHostFieldsAreRejectedForAbsoluteTarget() throws Exception {
        startAuthorityServer();
        assertThat(requestStatus("http://trusted.example/absolute-form-authority",
            "Host: trusted.example", "Host: attacker.example"), startsWith("HTTP/1.1 400 "));
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

    @Test
    void absoluteHttpTargetWithoutAuthorityIsRejected() throws Exception {
        startAuthorityServer();
        assertThat(requestStatus("http:/absolute-form-authority", "Host: trusted.example"),
            startsWith("HTTP/1.1 400 "));
    }

    private void startAuthorityServer() {
        server = httpServer().addHandler((request, response) -> {
            response.write(request.uri().getAuthority());
            return true;
        }).start();
    }

    private String requestAuthority(String target, String host) throws Exception {
        return requestBody(target, host);
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
