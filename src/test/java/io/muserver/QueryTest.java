package io.muserver;

import io.muserver.handlers.CSRFProtectionHandlerBuilder;
import okhttp3.*;
import okio.BufferedSink;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static scaffolding.ClientUtils.*;
import static scaffolding.ServerUtils.httpsServerForTest;

public class QueryTest {
    private MuServer server;
    @AfterEach public void stop() { scaffolding.MuAssert.stopAndCheck(server); }

    private OkHttpClient clientFor(Protocol protocol) {
        return client.newBuilder().protocols(protocol == Protocol.HTTP_2
            ? Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1) : Collections.singletonList(protocol)).build();
    }

    @Test public void fixedStreamedAndEmptyBodiesWorkOnBothProtocolsThroughContextRoutes() throws Exception {
        server = httpsServerForTest().withHttp2Config(Http2ConfigBuilder.http2EnabledIfAvailable())
            .addHandler(CSRFProtectionHandlerBuilder.csrfProtection())
            .addHandler(ContextHandlerBuilder.context("/context").addHandler(Method.QUERY, "/search", (req, resp, params) -> {
                resp.write(req.query().get("q") + ":" + req.readBodyAsString());
            })).start();
        for (Protocol protocol : Arrays.asList(Protocol.HTTP_1_1, Protocol.HTTP_2)) {
            OkHttpClient caller = clientFor(protocol);
            for (String value : Arrays.asList("query body 日本語", "")) {
                for (boolean streamed : new boolean[]{false, true}) {
                    RequestBody body = streamed ? new RequestBody() {
                        public MediaType contentType() { return MediaType.get("text/plain;charset=UTF-8"); }
                        public void writeTo(BufferedSink sink) throws IOException { sink.writeUtf8(value); }
                    } : RequestBody.create(value, MediaType.get("text/plain;charset=UTF-8"));
                    try (Response response = call(caller, request(server.uri().resolve("/context/search?q=uri"))
                        .header("Sec-Fetch-Site", "cross-site").method("QUERY", body))) {
                        assertEquals(protocol, response.protocol());
                        assertEquals(200, response.code());
                        assertEquals("uri:" + value, response.body().string());
                    }
                }
            }
        }
    }

    @Test public void methodTokensAreCaseSensitiveOnBothProtocols() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server = httpsServerForTest().withHttp2Config(Http2ConfigBuilder.http2EnabledIfAvailable())
            .addHandler((req, resp) -> {
                calls.incrementAndGet();
                if (req.method() != Method.HEAD) resp.write(req.method().name());
                return true;
            }).start();
        for (Protocol protocol : Arrays.asList(Protocol.HTTP_1_1, Protocol.HTTP_2)) {
            for (String method : Arrays.asList("query", "Query", "qUERY", "get", "Get", "head", "options", "UNKNOWN",
                "QUERY", "GET", "HEAD", "OPTIONS")) {
                int before = calls.get();
                boolean supported = Arrays.asList("QUERY", "GET", "HEAD", "OPTIONS").contains(method);
                try (Response response = call(clientFor(protocol), request(server.uri())
                    .header("Content-Type", "text/plain").method(method, method.equals("QUERY") ? RequestBody.EMPTY : null))) {
                    assertEquals(protocol, response.protocol());
                    assertEquals(supported ? 200 : 405, response.code(), protocol + " " + method);
                    assertEquals(before + (supported ? 1 : 0), calls.get());
                    if (supported) assertEquals(method.equals("HEAD") ? "" : method, response.body().string());
                }
            }
        }
    }

    @jakarta.ws.rs.Path("/rest") public static class ParameterResource {
        @io.muserver.rest.QUERY @jakarta.ws.rs.Consumes("text/plain")
        public String query(String body) { return body; }
    }

    @Test public void emptyContentTypeParametersAreValidOnBothProtocolsAndHandlerTypes() throws Exception {
        server = httpsServerForTest().withHttp2Config(Http2ConfigBuilder.http2EnabledIfAvailable())
            .addHandler(Method.QUERY, "/native", (req, resp, params) -> resp.write(req.readBodyAsString()))
            .addHandler(io.muserver.rest.RestHandlerBuilder.restHandler(new ParameterResource())).start();
        for (Protocol protocol : Arrays.asList(Protocol.HTTP_1_1, Protocol.HTTP_2)) {
            for (String path : Arrays.asList("/native", "/rest")) {
                for (String type : Arrays.asList("text/plain;", "text/plain;;;", "text/plain; ;charset=UTF-8;;")) {
                    try (Response response = call(clientFor(protocol), request(server.uri().resolve(path))
                        .header("Content-Type", type).method("QUERY", RequestBody.create("query body", (MediaType) null)))) {
                        assertEquals(protocol, response.protocol());
                        assertEquals(200, response.code(), path + " " + type);
                        assertEquals("query body", response.body().string());
                    }
                }
            }
        }
    }

    @Test public void jettyCanSendQueryAndDecodeForms() throws Exception {
        server = MuServerBuilder.httpServer().addHandler(Method.QUERY, "/", (req, resp, params) ->
            resp.write(req.form().get("q") + ":" + req.query().get("q"))).start();
        org.eclipse.jetty.client.api.ContentResponse response = jettyClient().newRequest(server.uri().resolve("/?q=uri"))
            .followRedirects(false).method("QUERY").content(new StringContentProvider("application/x-www-form-urlencoded", "q=hello+world", StandardCharsets.UTF_8)).send();
        assertEquals(org.eclipse.jetty.http.HttpVersion.HTTP_1_1, response.getVersion());
        assertEquals(200, response.getStatus());
        assertEquals("hello world:uri", response.getContentAsString());
    }

    @Test public void invalidContentTypesAreRejectedBeforeContinueAndDispatch() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server = MuServerBuilder.httpServer().addHandler((req, resp) -> { calls.incrementAndGet(); return true; }).start();
        for (String header : Arrays.asList("", "Content-Type: \r\n", "Content-Type: invalid\r\n",
            "Content-Type: text/plain, application/json\r\n", "Content-Type: text/plain; charset=\"unterminated\r\n",
            "Content-Type: text/plain; charset\r\n", "Content-Type: text/plain; charset=\r\n",
            "Content-Type: text/plain; =UTF-8\r\n", "Content-Type: text/pl ain\r\n", "Content-Type: */*\r\n", "Content-Type: text/plain\r\nContent-Type: application/json\r\n")) {
            try (Socket socket = new Socket(server.uri().getHost(), server.uri().getPort())) {
                socket.setSoTimeout(5000);
                socket.getOutputStream().write(("QUERY / HTTP/1.1\r\nHost: localhost\r\n" + header
                    + "Content-Length: 20\r\nExpect: 100-continue\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(response.startsWith("HTTP/1.1 400"), response);
                assertFalse(response.contains("100 Continue"), response);
            }
        }
        assertEquals(0, calls.get());
    }

    @Test public void rejectedHttp2StreamsDoNotPreventSubsequentRequests() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server = httpsServerForTest().withHttp2Config(Http2ConfigBuilder.http2EnabledIfAvailable())
            .addHandler((req, resp) -> { calls.incrementAndGet(); resp.write(req.readBodyAsString()); return true; }).start();
        OkHttpClient caller = clientFor(Protocol.HTTP_2);
        for (String contentType : Arrays.asList("", " ", "invalid", "text/plain; charset", "text/plain; charset=", "text/plain; =UTF-8", "text/plain; charset=\"")) {
            Request.Builder req = request(server.uri()).method("QUERY", RequestBody.create("body", (MediaType) null));
            if (!contentType.isEmpty()) req.header("Content-Type", contentType);
            try (Response response = call(caller, req)) {
                assertEquals(Protocol.HTTP_2, response.protocol());
                assertEquals(400, response.code());
                response.body().string();
            }
        }
        assertEquals(0, calls.get());
        try (Response response = call(caller, request(server.uri()).method("QUERY", RequestBody.create("ok", MediaType.get("text/plain"))))) {
            assertEquals(Protocol.HTTP_2, response.protocol());
            assertEquals("ok", response.body().string());
        }
        assertEquals(1, calls.get());
    }

    @Test public void asyncBodyReadingWorksOnBothProtocols() throws Exception {
        server = httpsServerForTest().withHttp2Config(Http2ConfigBuilder.http2EnabledIfAvailable())
            .addHandler((req, resp) -> {
                AsyncHandle handle = req.handleAsync();
                handle.setReadListener(new RequestBodyListener() {
                    public void onDataReceived(java.nio.ByteBuffer buffer, DoneCallback done) {
                        handle.write(buffer, done);
                    }
                    public void onComplete() { handle.complete(); }
                    public void onError(Throwable error) { handle.complete(error); }
                });
                return true;
            }).start();
        for (Protocol protocol : Arrays.asList(Protocol.HTTP_1_1, Protocol.HTTP_2)) {
            try (Response response = call(clientFor(protocol), request(server.uri()).method("QUERY", RequestBody.create("async body", MediaType.get("text/plain"))))) {
                assertEquals(protocol, response.protocol()); assertEquals(200, response.code());
                assertEquals("async body", response.body().string());
            }
        }
    }

    @Test public void httpsRedirectorStillRejectsQueryAndRedirectsGet() throws Exception {
        server = MuServerBuilder.httpServer().addHandler(io.muserver.handlers.HttpsRedirectorBuilder.toHttpsPort(443)).start();
        try (Response response = call(request(server.uri()).method("QUERY", RequestBody.create("body", MediaType.get("text/plain"))))) {
            assertEquals(400, response.code()); assertNull(response.header("Location"));
            assertTrue(response.body().string().contains("Please use the HTTPS endpoint"));
        }
        try (Response response = call(request(server.uri()))) { assertEquals(301, response.code()); }
    }

    @Test public void sizeLimitsApplyToQuery() throws Exception {
        server = httpsServerForTest().withHttp2Config(Http2ConfigBuilder.http2EnabledIfAvailable()).withMaxRequestSize(3)
            .addHandler((req, resp) -> { resp.write(req.readBodyAsString()); return true; }).start();
        for (Protocol protocol : Arrays.asList(Protocol.HTTP_1_1, Protocol.HTTP_2)) {
            for (boolean streamed : new boolean[]{false, true}) {
                RequestBody body = streamed ? new RequestBody() {
                    public MediaType contentType() { return MediaType.get("text/plain"); }
                    public void writeTo(BufferedSink sink) throws IOException { sink.writeUtf8("too large"); }
                } : RequestBody.create("too large", MediaType.get("text/plain"));
                try (Response response = call(clientFor(protocol), request(server.uri()).method("QUERY", body))) {
                    assertEquals(protocol, response.protocol());
                    assertEquals(413, response.code());
                }
            }
        }
    }
}
