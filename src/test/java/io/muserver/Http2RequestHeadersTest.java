package io.muserver;

import okhttp3.internal.concurrent.TaskRunner;
import okhttp3.internal.connection.BufferedSocketKt;
import okhttp3.internal.http2.ErrorCode;
import okhttp3.internal.http2.Header;
import okhttp3.internal.http2.Http2Stream;
import okhttp3.internal.http2.StreamResetException;
import okio.Buffer;
import okio.Okio;
import org.junit.After;
import org.junit.Test;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;
import static scaffolding.ClientUtils.sslContextForTesting;
import static scaffolding.ClientUtils.veryTrustingTrustManager;
import static scaffolding.MuAssert.stopAndCheck;
import static scaffolding.ServerUtils.httpsServerForTest;

public class Http2RequestHeadersTest {
    private MuServer server;

    @After
    public void stop() {
        stopAndCheck(server);
    }

    @Test
    public void conflictsOnlyResetTheirStreamsAndAreCountedBeforeDispatch() throws Exception {
        AtomicInteger handled = new AtomicInteger();
        AtomicInteger rejectedResponses = new AtomicInteger();
        CompletableFuture<HttpConnection> holding = new CompletableFuture<>();
        server = httpsServerForTest().withHttp2Config(Http2ConfigBuilder.http2Enabled()).withGzipEnabled(false)
            .addRequestRejectListener(info -> rejectedResponses.incrementAndGet())
            .addHandler((request, response) -> {
                handled.incrementAndGet();
                if (request.method() == Method.POST) {
                    holding.complete(request.connection());
                    response.write(request.readBodyAsString());
                } else {
                    response.write("accepted");
                }
                return true;
            }).start();

        try (Client client = new Client(server)) {
            Http2Stream active = client.stream(headers("POST", "example.test"), true);
            active.getSink().write(new Buffer().writeUtf8("body"), 4);
            active.getSink().flush();
            HttpConnection serverConnection = holding.get(5, TimeUnit.SECONDS);

            for (String method : List.of("GET", "POST", "QUERY")) {
                for (String host : List.of("different.test", "example.test:8443")) {
                    List<Header> headers = headers(method, "example.test", host);
                    headers.add(new Header("content-type", "text/plain"));
                    // Also exercise rejection of streams whose request body has not ended.
                    assertReset(client.stream(headers, !method.equals("GET")));
                }
            }
            assertEquals(6, server.stats().invalidHttpRequests());
            assertEquals(6, serverConnection.invalidHttpRequests());
            assertEquals(0, rejectedResponses.get());
            assertEquals(1, handled.get());

            active.getSink().close();
            assertResponse(active, "body");
            assertResponse(client.stream(headers("GET", "example.test"), false), "accepted");
            assertEquals(2, handled.get());
        }
    }

    @Test
    public void matchingAuthoritiesUseTheSelectedValueForTheApplication() throws Exception {
        server = httpsServerForTest().withHttp2Config(Http2ConfigBuilder.http2Enabled()).withGzipEnabled(false)
            .addHandler((request, response) -> {
                response.write(request.headers().get("host") + "|" + request.uri().getRawAuthority()
                    + "|" + request.headers().getAll("host").size());
                return true;
            }).start();
        try (Client client = new Client(server)) {
            for (String[] example : new String[][]{
                {"Example.test"},
                {"Example.test", "Example.test"},
                {"Example.test", "example.test", "EXAMPLE.TEST"},
                {"example.test:8443", "EXAMPLE.TEST:8443"},
                {"[2001:db8::1]:443", "[2001:DB8::1]:443"},
                {null, "Example.test:443", "example.test:443"}
            }) {
                String selected = example[0] == null ? example[1] : example[0];
                String[] hosts = java.util.Arrays.copyOfRange(example, 1, example.length);
                assertResponse(client.stream(headers("GET", example[0], hosts), false),
                    selected + "|" + selected + "|1");
            }
        }
    }

    @Test
    public void invalidAuthoritiesDoNotCloseTheConnection() throws Exception {
        startAcceptingServer();
        try (Client client = new Client(server)) {
            for (String authority : new String[]{null, "user@example.test", "example.test/path", "example.test?query",
                "example.test#fragment", "example.test:bad", "example.test:65536", "[::1", ""}) {
                assertReset(client.stream(headers("GET", authority), false));
            }
            assertReset(client.stream(headers("GET", "example.test", "example.test", "different.test"), false));
            assertReset(client.stream(headers("GET", null, "example.test", "different.test"), false));
            for (String[] mismatch : new String[][]{
                {"example.test", "example.test:443"},
                {"example.test:443", "example.test:0443"},
                {"[::1]", "[0:0:0:0:0:0:0:1]"}
            }) {
                assertReset(client.stream(headers("GET", mismatch[0], mismatch[1]), false));
            }
            assertResponse(client.stream(headers("GET", "example.test"), false), "accepted");
        }
    }

    @Test
    public void nettyStillRejectsInvalidFieldsWithoutHttp1Conversion() throws Exception {
        startAcceptingServer();
        try (Client client = new Client(server)) {
            for (Header invalid : List.of(new Header("bad name", "value"), new Header("x-test", "one\r\ntwo"),
                new Header("connection", "close"), new Header("te", "gzip"))) {
                List<Header> headers = headers("GET", "example.test");
                headers.add(invalid);
                assertReset(client.stream(headers, false));
            }
            assertResponse(client.stream(headers("GET", "example.test"), false), "accepted");
        }
    }

    @Test
    public void multipartConversionUsesTheValidatedHost() throws Exception {
        server = httpsServerForTest().withHttp2Config(Http2ConfigBuilder.http2Enabled()).withGzipEnabled(false)
            .addHandler((request, response) -> {
                response.write(request.form().get("field") + "|" + request.headers().get("host"));
                return true;
            }).start();
        try (Client client = new Client(server)) {
            for (String authority : new String[]{"Example.test", null}) {
                List<Header> headers = headers("POST", authority, "example.test", "EXAMPLE.TEST");
                headers.add(new Header("content-type", "multipart/form-data; boundary=example-boundary"));
                Http2Stream stream = client.stream(headers, true);
                Buffer body = new Buffer().writeUtf8("--example-boundary\r\n"
                    + "Content-Disposition: form-data; name=\"field\"\r\n\r\nvalue\r\n--example-boundary--\r\n");
                stream.getSink().write(body, body.size());
                stream.getSink().close();
                assertResponse(stream, "value|" + (authority == null ? "example.test" : authority));
            }
        }
    }

    private void startAcceptingServer() {
        server = httpsServerForTest().withHttp2Config(Http2ConfigBuilder.http2Enabled()).withGzipEnabled(false)
            .addHandler((request, response) -> { response.write("accepted"); return true; }).start();
    }

    private static List<Header> headers(String method, String authority, String... hosts) {
        List<Header> headers = new ArrayList<>(List.of(new Header(":method", method),
            new Header(":path", "/"), new Header(":scheme", "https")));
        if (authority != null) headers.add(new Header(":authority", authority));
        for (String host : hosts) headers.add(new Header("host", host));
        return headers;
    }

    private static void assertReset(Http2Stream stream) {
        StreamResetException error = assertThrows(StreamResetException.class, () -> stream.takeHeaders(false));
        assertEquals(ErrorCode.PROTOCOL_ERROR, error.errorCode);
    }

    private static void assertResponse(Http2Stream stream, String body) throws Exception {
        assertEquals("200", stream.takeHeaders(false).get(":status"));
        assertEquals(body, Okio.buffer(stream.getSource()).readUtf8());
    }

    private static class Client implements AutoCloseable {
        private final SSLSocket socket;
        private final okhttp3.internal.http2.Http2Connection connection;

        private Client(MuServer server) throws Exception {
            socket = (SSLSocket) sslContextForTesting(veryTrustingTrustManager).getSocketFactory()
                .createSocket(server.uri().getHost(), server.uri().getPort());
            SSLParameters parameters = socket.getSSLParameters();
            parameters.setApplicationProtocols(new String[]{"h2"});
            socket.setSSLParameters(parameters);
            socket.setSoTimeout(5000);
            socket.startHandshake();
            assertEquals("h2", socket.getApplicationProtocol());
            connection = new okhttp3.internal.http2.Http2Connection.Builder(true, TaskRunner.INSTANCE)
                .socket(BufferedSocketKt.asBufferedSocket(socket), "mu-server header test").build();
            connection.start();
        }

        private Http2Stream stream(List<Header> headers, boolean hasBody) throws Exception {
            Http2Stream stream = connection.newStream(headers, hasBody);
            stream.readTimeout().timeout(5, TimeUnit.SECONDS);
            stream.writeTimeout().timeout(5, TimeUnit.SECONDS);
            // OkHttp buffers HEADERS while waiting for a body; these tests may intentionally send none.
            connection.flush();
            return stream;
        }

        @Override
        public void close() throws Exception {
            connection.close();
            socket.close();
        }
    }
}
