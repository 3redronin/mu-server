package io.muserver;

import okhttp3.MediaType;
import okhttp3.RequestBody;
import org.eclipse.jetty.client.api.ContentResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scaffolding.*;

import javax.net.ssl.*;
import java.io.*;
import java.net.Socket;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static io.muserver.MuServerBuilder.httpServer;
import static io.muserver.MuServerBuilder.httpsServer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;
import static scaffolding.MuAssert.assertEventually;

public class MuServer2Test {

    private static final Logger log = LoggerFactory.getLogger(MuServer2Test.class);
    private MuServer server;

    @Test
    public void canStartAndStopHttp() throws Exception {
        var s = "Hello ".repeat(1000);
        int port = 0;
        for (int i = 0; i < 2; i++) {
            int finalI = i;
            MuServerBuilder muServerBuilder = MuServerBuilder.muServer()
                .withHttpPort(port)
                .addHandler(Method.GET, "/blah", (request, response, pathParams) -> {
                    response.write("Hello " + s + finalI);
                });
            var server = muServerBuilder.start();
            log.info("Started at " + server.uri());
            port = server.uri().getPort(); // so next time we reuse the same port

            try (var resp = call(request(server.uri().resolve("/blah")))) {
                assertThat(resp.code(), equalTo(200));
                assertThat(resp.body().string(), equalTo("Hello " + s + i));
            }
            server.stop();
            try (var resp = call(request(server.uri().resolve("/blah")))) {
                fail("Should not work");
            } catch (Exception ex) {
            }
        }
    }

    @Test
    public void clientsCanInitiateTlsShutdowns() throws Exception {
        MuServerBuilder muServerBuilder = httpsServer();
        server = muServerBuilder.start();
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(new KeyManager[0], new TrustManager[]{new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] x509Certificates, String s) {
                }

                public void checkServerTrusted(X509Certificate[] x509Certificates, String s) {
                }

                public X509Certificate[] getAcceptedIssuers() {
                    return null;
                }
            }},
            new SecureRandom());
        HttpsURLConnection conn = (HttpsURLConnection) server.uri().toURL().openConnection();
        conn.setSSLSocketFactory(ctx.getSocketFactory());
        conn.setHostnameVerifier((arg0, arg1) -> true);
        conn.setConnectTimeout(5000);
        conn.connect();
        assertEventually(() -> server.activeConnections(), hasSize(1));
        HttpConnection httpConnection = server.activeConnections().stream().findAny().get();
        System.out.println("httpConnection = " + httpConnection);
        conn.disconnect();
        assertEventually(() -> server.activeConnections(), empty());

    }


    @Test
    public void serverCanInitiateShutdownOnTLS() throws Exception {
        String hello = "hello ".repeat(1000);
        var server = httpsServer().addHandler((request, response) -> {
            response.write(hello);
            return true;
        }).start();
        var client = Http1Client.connect(server)
            .writeRequestLine(Method.GET, "/")
            .flushHeaders();
        client.readLine();
        assertThat(client.readBody(client.readHeaders()), equalTo(hello));
        new Thread(server::stop).start();
        assertThrows(EOFException.class, client::readLine);
        client.out().close();
        assertEventually(server::activeConnections, empty());
    }

    @Test
    public void serverCanInitiateGracefulShutdownOverHttp() throws Exception {
        var events = new ConcurrentLinkedQueue<String>() {
            @Override
            public boolean add(String s) {
                log.info("Test event: " + s);
                return super.add(s);
            }
        };
        var serverStoppedLatch = new CountDownLatch(1);

        var server = httpServer()
            .addHandler(Method.GET, "/blah", (request, response, pathParams) -> {
                events.add("Writing response");
                response.write("Hello\r\n");
            }).start();

        Socket clientConnection = new Socket(server.uri().getHost(), server.uri().getPort());
        // Get the input and output streams
        PrintWriter out = new PrintWriter(clientConnection.getOutputStream(), true);
        out.print("GET /blah HTTP/1.1\r\n");
        out.print("Host: " + server.uri().getAuthority() + "\r\n");
        out.print("\r\n");
        out.flush();

        var latch = new CountDownLatch(1);

        new Thread(() -> {
            try {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(clientConnection.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log.info("Client read line = " + line);
                        if (line.equals("Hello")) {
                            events.add("Got line: " + line);
                            new Thread(() -> {
                                try {
                                    Thread.sleep(500);
                                } catch (InterruptedException e) {
                                }
                                events.add("Stop initiated");
                                server.stop();
                                serverStoppedLatch.countDown();
                            }).start();
                        }
                    }
                    events.add("Got EOF");
                }
                out.close();
                clientConnection.close();
                events.add("Closed reader");
            } catch (IOException e) {
                events.add("Exception reading: " + e.getMessage());
            } finally {
                latch.countDown();
            }
        }).start();


        MuAssert.assertNotTimedOut("Waiting for client EOF", latch);
        MuAssert.assertNotTimedOut("Waiting for server stop", serverStoppedLatch);
        assertThat("Actual events: " + events, events, contains("Writing response", "Got line: Hello", "Stop initiated", "Got EOF", "Closed reader"));
        assertThat(server.activeConnections(), empty());
    }

    @Test
    public void clientCanInitiateGracefulShutdownOverHttp() throws Exception {
        var events = new ConcurrentLinkedQueue<String>() {
            @Override
            public boolean add(String s) {
                log.info("Test event: " + s);
                return super.add(s);
            }
        };

        server = httpServer()
            .addHandler(Method.GET, "/blah", (request, response, pathParams) -> {
                events.add("Writing response");
                response.write("Hello\r\n");
            }).start();

        Socket clientConnection = new Socket(server.uri().getHost(), server.uri().getPort());
        // Get the input and output streams
        PrintWriter out = new PrintWriter(clientConnection.getOutputStream(), true);
        out.print("GET /blah HTTP/1.1\r\n");
        out.print("Host: " + server.uri().getAuthority() + "\r\n");
        out.print("\r\n");
        out.flush();

        var latch = new CountDownLatch(1);

        new Thread(() -> {
            try {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(clientConnection.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log.info("Client read line = " + line);
                        if (line.equals("Hello")) {
                            events.add("Got line: " + line);
                            out.close();
                            events.add("Closed output stream");
                        }
                    }
                    events.add("Got EOF");
                }
                clientConnection.close();
                events.add("Closed reader");
            } catch (IOException e) {
                events.add("Exception reading");
            } finally {
                latch.countDown();
            }
        }).start();


        assertEventually(server::activeConnections, empty());
        MuAssert.assertNotTimedOut("Waiting for client EOF", latch);
        assertThat("Actual events: " + events, events, contains("Writing response", "Got line: Hello", "Closed output stream", "Exception reading"));
        assertThat(server.activeConnections(), empty());
    }


    @Test
    public void canStartAndStopHttps() throws Exception {
        for (int i = 0; i < 1; i++) {
            int finalI = i;
            MuServerBuilder muServerBuilder = MuServerBuilder.muServer()
                .withHttpsPort(0)
                .addHandler(Method.GET, "/blah", (request, response, pathParams) -> {
                    response.write("Hello " + finalI);
                });
            var server = muServerBuilder.start();
            log.info("Started at " + server.uri());

            try (var resp = call(request(server.uri().resolve("/blah")))) {
                assertThat(resp.code(), equalTo(200));
                assertThat(resp.body().string(), equalTo("Hello " + i));
            }
            server.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "jetty", "okhttpclient" })
    public void tls12Available(String client) throws Exception {
        var theCipher = "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384";
        MuServerBuilder muServerBuilder = httpsServer()
            .withHttpsConfig(HttpsConfigBuilder.httpsConfig()
                .withProtocols("TLSv1.2")
                .withCipherFilter((supportedCiphers, defaultCiphers) -> List.of(theCipher))
            )
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                HttpConnection con = request.connection();
                response.write(con.isHttps() + " " + con.httpsProtocol() + " " + con.cipher());
            });
        server = muServerBuilder.start();

        if (client.equals("jetty")) {
            ContentResponse resp = ClientUtils.jettyClient().GET(server.uri());
            assertThat(resp.getStatus(), equalTo(200));
            assertThat(resp.getContentAsString(), equalTo("true TLSv1.2 " + theCipher));
            resp = ClientUtils.jettyClient().GET(server.uri());
            assertThat(resp.getStatus(), equalTo(200));
            assertThat(resp.getContentAsString(), equalTo("true TLSv1.2 " + theCipher));
        } else {
            try (var resp = call(request(server.uri().resolve("/")))) {
                assertThat(resp.code(), equalTo(200));
                assertThat(resp.body().string(), equalTo("true TLSv1.2 " + theCipher));
            }
            try (var resp = call(request(server.uri().resolve("/")))) {
                assertThat(resp.code(), equalTo(200));
                assertThat(resp.body().string(), equalTo("true TLSv1.2 " + theCipher));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "jetty", "okhttpclient" })
    public void tls13Available(String client) throws Exception {
        AtomicReference<String> theCipher = new AtomicReference<>();
        server = httpsServer()
            .withHttpsConfig(HttpsConfigBuilder.httpsConfig()
                .withProtocols("TLSv1.2", "TLSv1.3")
                .withCipherFilter((supportedCiphers, defaultCiphers) -> {
                    theCipher.set(defaultCiphers.get(0));
                    return List.of(theCipher.get());
                })
            )
            .addHandler(Method.GET, "/", new RouteHandler() {
                @Override
                public void handle(MuRequest request, MuResponse response, Map<String, String> pathParams) throws Exception {
                    HttpConnection con = request.connection();
                    response.write(con.isHttps() + " " + con.httpsProtocol() + " " + con.cipher());
                }
            }).start();
        if (client.equals("jetty")) {
            ContentResponse resp = ClientUtils.jettyClient().GET(server.uri());
            assertThat(resp.getStatus(), equalTo(200));
            assertThat(resp.getContentAsString(), equalTo("true TLSv1.3 " + theCipher));
            resp = ClientUtils.jettyClient().GET(server.uri());
            assertThat(resp.getStatus(), equalTo(200));
            assertThat(resp.getContentAsString(), equalTo("true TLSv1.3 " + theCipher));
        } else {
            try (var resp = call(request(server.uri().resolve("/")))) {
                assertThat(resp.code(), equalTo(200));
                assertThat(resp.body().string(), equalTo("true TLSv1.3 " + theCipher));
            }
            try (var resp = call(request(server.uri().resolve("/")))) {
                assertThat(resp.code(), equalTo(200));
                assertThat(resp.body().string(), equalTo("true TLSv1.3 " + theCipher));
            }
        }
    }


    @Test
    public void canGetServerInfo() throws Exception {
        var theCipher = "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384";
        MuServerBuilder muServerBuilder = httpsServer()
            .withHttpsConfig(HttpsConfigBuilder.httpsConfig()
                .withProtocols("TLSv1.2")
                .withCipherFilter((supportedCiphers, defaultCiphers) -> List.of(theCipher))
            );
        server = muServerBuilder.start();
        assertThat(server.sslInfo().providerName(), not(nullValue()));
        assertThat(server.sslInfo().ciphers(), contains(theCipher));
        assertThat(server.sslInfo().protocols(), contains("TLSv1.2"));
        assertThat(server.sslInfo().certificates(), hasSize(1));
    }


    @Test
    public void ifNoCommonCiphersThenItDoesNotLoad() throws Exception {
        var theCipher = "TLS_AES_128_GCM_SHA256";
        MuServerBuilder muServerBuilder = httpsServer()
            .withHttpsConfig(HttpsConfigBuilder.httpsConfig()
                .withProtocols("TLSv1.2")
                .withCipherFilter((supportedCiphers, defaultCiphers) -> List.of(theCipher))
            );
        server = muServerBuilder.start();
        assertThrows(UncheckedIOException.class, () -> {
            try (var ignored = call(request(server.uri().resolve("/")))) {
            }
        });
        assertThat(server.stats().failedToConnect(), equalTo(1L));
    }


    @ParameterizedTest
    @ValueSource(strings = {"http", "https"})
    public void canChunk(String type) throws Exception {
        server = ServerUtils.httpsServerForTest(type)
            .withHttpsPort(0)
            .addHandler(Method.GET, "/blah", (request, response, pathParams) -> {
                response.headers().set(HeaderNames.TRAILER, "server-timing");
                response.sendChunk("Hello");
                response.sendChunk(" ");
                response.sendChunk("world");
                response.trailers().set(HeaderNames.SERVER_TIMING, new ParameterizedHeaderWithValue("total", Map.of("dur", "123.4")));
            }).start();
        log.info("Started at " + server.uri());

        try (var resp = call(request(server.uri().resolve("/blah")))) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.body().string(), equalTo("Hello world"));
            assertThat(resp.trailers().get("server-timing"), nullValue());
        }

        try (var resp = call(request(server.uri().resolve("/blah"))
            .header("TE", "trailers")
        )) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.body().string(), equalTo("Hello world"));
            assertThat(resp.trailers().get("server-timing"), equalTo("total;dur=123.4"));
        }
    }

    @Test
    public void canWriteChunksToOutputStreamWithoutFlushing() throws Exception {
        MuServerBuilder muServerBuilder = MuServerBuilder.muServer()
            .withHttpsPort(0)
            .addHandler(Method.GET, "/blah", (request, response, pathParams) -> {
                response.writer().write("Hello");
            });
        server = muServerBuilder.start();
        log.info("Started at " + server.uri());

        try (var resp = call(request(server.uri().resolve("/blah")))) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.headers("content-type"), contains("text/plain;charset=utf-8"));
            assertThat(resp.body().string(), equalTo("Hello"));
        }

    }


    @Test
    public void canWriteChunksToOutputStream() throws Exception {
        MuServerBuilder muServerBuilder = MuServerBuilder.muServer()
            .withHttpsPort(0)
            .addHandler(Method.GET, "/blah", (request, response, pathParams) -> {
                response.headers().set(HeaderNames.TRAILER, "server-timing");
                response.writer().write("Hello");
                response.writer().flush();
                response.writer().write(" ");
                response.writer().flush();
                response.writer().write("world");
                response.trailers().set(HeaderNames.SERVER_TIMING, new ParameterizedHeaderWithValue("total", Map.of("dur", "123.4")));
            });
        server = muServerBuilder.withGzipEnabled(false).start();
        log.info("Started at " + server.uri());

        try (var resp = call(request(server.uri().resolve("/blah")))) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.headers("content-type"), contains("text/plain;charset=utf-8"));
            assertThat(resp.body().string(), equalTo("Hello world"));
            assertThat(resp.trailers().get("server-timing"), nullValue());
        }

        try (var resp = call(request(server.uri().resolve("/blah"))
            .header("TE", "trailers")
        )) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.headers("content-type"), contains("text/plain;charset=utf-8"));
            assertThat(resp.body().string(), equalTo("Hello world"));
            assertThat(resp.trailers().get("server-timing"), equalTo("total;dur=123.4"));
        }

    }

    @Test
    public void canWriteLargeStrings() throws Exception {
        var rando = StringUtils.randomStringOfLength(200000);
        server = httpsServer()
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.write(rando);
            }).start();
        try (var resp = call(request(server.uri()))) {
            assertThat(resp.body().string(), equalTo(rando));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"http", "https"})
    public void canReadLargeStrings(String type) throws Exception {
        var rando = StringUtils.randomStringOfLength(20000);
        var received = new AtomicReference<String>();
        server = ServerUtils.testServer(type)
            .addHandler(Method.POST, "/", (request, response, pathParams) -> {
                received.set(request.readBodyAsString());
            }).start();
        call(request(server.uri()).post(RequestBody.create(rando, MediaType.get("text/plain")))).close();
        assertThat(received.get(), equalTo(rando));
    }

    @Test
    public void canWriteFixedLengthToOutputStream() throws Exception {
        MuServerBuilder muServerBuilder = MuServerBuilder.muServer()
            .withHttpsPort(0)
            .addHandler(Method.GET, "/blah", (request, response, pathParams) -> {
                response.headers().set(HeaderNames.TRAILER, "server-timing");
                response.headers().set(HeaderNames.CONTENT_LENGTH, 11);
                response.writer().write("Hello");
                response.writer().flush();
                response.writer().write(" ");
                response.writer().flush();
                response.writer().write("world");
                response.trailers().set(HeaderNames.SERVER_TIMING, new ParameterizedHeaderWithValue("total", Map.of("dur", "123.4")));
            });
        server = muServerBuilder.start();
        log.info("Started at " + server.uri());

        try (var resp = call(request(server.uri().resolve("/blah")))) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.headers("content-type"), contains("text/plain;charset=utf-8"));
            assertThat(resp.body().string(), equalTo("Hello world"));
            assertThat(resp.trailers().get("server-timing"), nullValue());
        }

        try (var resp = call(request(server.uri().resolve("/blah"))
            .header("TE", "trailers")
        )) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.headers("content-type"), contains("text/plain;charset=utf-8"));
            assertThat(resp.body().string(), equalTo("Hello world"));
            assertThat(resp.trailers().get("server-timing"), nullValue());
        }

    }

    @AfterEach
    public void stopIt() {
        if (server != null) {
            server.stop();
        }
    }

}