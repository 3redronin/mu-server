package io.muserver;

import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.internal.http2.ErrorCode;
import okhttp3.internal.http2.StreamResetException;
import okio.BufferedSink;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import scaffolding.FileUtils;
import scaffolding.MuAssert;
import scaffolding.RawClient;
import scaffolding.ServerUtils;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.*;

import static io.muserver.MuServerBuilder.httpServer;
import static io.muserver.MuServerBuilder.muServer;
import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.core.IsEqual.equalTo;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;
import static scaffolding.MuAssert.assertEventually;

@Timeout(20)
public class MuServerTest {

    private MuServer server;
    private static String hostname;

    @BeforeAll
    public static void setup() {
        // these tests seem to crash the JVM with certain VPNs
        var hostnameTestDisabled = Objects.equals("true", System.getenv("HOSTNAME_TEST_DISABLED"));
        if (hostnameTestDisabled) {
            hostname = null;
        } else {
            try {
                hostname = InetAddress.getLocalHost().getHostName().toLowerCase();
            } catch (Exception e) {
                hostname = null;
            }
        }
    }

    @Test
    public void portZeroCanBeUsed() {
        server = ServerUtils.httpsServerForTest().start();
        try (Response resp = call(request().url(server.uri().toString()))) {
            assertThat(resp.code(), is(404));
        }
    }

    @Test
    public void unhandledExceptionsResultIn500sIfNoResponseSent() {
        server = ServerUtils.httpsServerForTest()
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                throw new RuntimeException("I'm the fire starter");
            })
            .start();
        try (Response resp = call(request().url(server.uri().toString()))) {
            assertThat(resp.code(), is(500));
        }
    }

    @Test
    public void statsAreAvailableAfterResponseFinished() throws Exception {
        server = ServerUtils.httpsServerForTest()
            .addResponseCompleteListener(info -> {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    throw new RuntimeException("Hmm");
                }
            })
            .addHandler(Method.GET, "/", (request, response, pathParams) -> response.write("X"))
            .start();
        try (Response resp = call(request(server.uri()))) {
            assertThat(resp.body().string(), is("X"));
        }
        assertEventually(() -> server.stats().activeRequests(), empty());
        assertThat(server.stats().completedRequests(), equalTo(1L));
    }

    @Test
    @Timeout(60)
    public void multipleWritesWorkRight() throws Exception {
        server = ServerUtils.httpsServerForTest()
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.write("Hello");
            })
            .start();
        for (int i = 0; i < 100; i++) {
            try (Response resp = call(request(server.uri().resolve("/?i=" + i)))) {
                assertThat("Error on i=" + i, resp.body().string(), is("Hello"));
            }
        }
        assertEventually(() -> server.stats().completedRequests(), equalTo(100L));
        assertThat(server.stats().completedConnections(), lessThan(50L)); // just make sure it's not one connection per request
    }


    @Test
    public void unhandledExceptionsAreLoggedAndTheResponseIsKilledEarly() {
        server = ServerUtils.httpsServerForTest()
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.status(200);
                response.writer().print("Hello");
                throw new RuntimeException("I'm the fire starter");
            })
            .start();
        try (Response resp = call(request().url(server.uri().toString()))) {
            assertThat(resp.code(), is(200));
        } catch (Exception ex) {
            MuAssert.assertIOException(ex);
        }
    }

    @Test
    public void queryToStringIsAUrlString() throws IOException {
        server = ServerUtils.httpsServerForTest()
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.write(request.query().toString());
            })
            .start();
        try (Response resp = call(request().url(server.uri() + "?hi=a%20thing&b=another"))) {
            assertThat(resp.body().string(), equalTo("hi=a%20thing&b=another"));
        }
    }

    @Test
    public void syncHandlersSupportedAndStateCanBePassedThroughHandlers() throws IOException {
        List<String> handlersHit = new ArrayList<>();
        String randomText = UUID.randomUUID().toString();

        server = ServerUtils.httpsServerForTest()
            .withHttpPort(12809)
            .addHandler((request, response) -> {
                handlersHit.add("Logger");
                request.attribute("random", randomText);
                return false;
            })
            .addHandler(Method.GET, "/blah", (request, response, pathParams) -> {
                handlersHit.add("BlahHandler");
                response.status(202);
                response.write("This is a test and this is the state: " + request.attribute("random")
                    + " and this does not exist: " + request.attribute("blah"));
            })
            .addHandler((request, response) -> {
                handlersHit.add("LastHandler");
                return true;
            })
            .start();

        try (Response resp = call(request().url("http://localhost:12809/blah"))) {
            assertThat(resp.code(), is(202));
            assertThat(resp.body().string(), equalTo("This is a test and this is the state: "
                + randomText + " and this does not exist: null"));
            assertThat(handlersHit, equalTo(asList("Logger", "BlahHandler")));
        }
    }

    @Test
    public void ifBoundToLocalhostThenLoopbackAddressIsUsed() throws IOException {
        Assumptions.assumeTrue(hostname != null);

        for (String host : asList("127.0.0.1", "localhost")) {
            MuServer server = ServerUtils.httpsServerForTest()
                .withInterface(host)
                .addHandler(Method.GET, "/", (req, resp, pp) -> resp.write("Hello from " + req.server().address().getAddress().getHostAddress()))
                .start();
            try (Response resp = call(request().url(server.uri().toString()))) {
                assertThat(resp.body().string(), equalTo("Hello from 127.0.0.1"));
            } finally {
                server.stop();
            }
        }
    }

    @Test
    public void ifBoundTo0000ThenExternalAccessIsPossible() throws IOException {
        Assumptions.assumeTrue(hostname != null);

        server = ServerUtils.httpsServerForTest()
            .withInterface("0.0.0.0")
            .addHandler(Method.GET, "/", (req, resp, pp) -> resp.write("Hello from " + server.address().getHostString()))
            .start();
        try (Response resp = call(request().url("https://" + hostname + ":" + server.uri().getPort()))) {
            assertThat(resp.body().string(), startsWith("Hello from 0"));
        }
    }

    @Test
    public void ifBoundToHostnameThenExternalAccessIsPossible() throws IOException {
        Assumptions.assumeTrue(hostname != null);

        server = ServerUtils.httpsServerForTest()
            .withInterface(hostname)
            .addHandler(Method.GET, "/", (req, resp, pp) -> resp.write("Hello from " + server.uri().getHost()))
            .start();
        try (Response resp = call(request().url("https://" + hostname + ":" + server.uri().getPort()))) {
            assertThat(resp.body().string(), equalTo("Hello from " + hostname));
        }
    }

    @Test
    public void theClientIpAddressOrSomethingLikeThatIsAvailable() throws IOException {
        server = ServerUtils.httpsServerForTest()
            .addHandler(Method.GET, "/", (req, resp, pp) -> resp.write("Hello there " + req.remoteAddress()))
            .start();
        try (Response resp = call(request().url(server.uri().toString()))) {
            assertThat(resp.body().string(), containsString("."));
        }
    }

    @Test
    public void ifNoPortsDefinedAFriendlyMessageIsReturned() {
        try {
            muServer().start();
            Assertions.fail("No exception thrown");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage(), is(equalTo("No ports were configured. Please call MuServerBuilder.withHttpPort(int) or MuServerBuilder.withHttpsPort(int)")));
        }
    }

    @Test
    public void returnsA405ForUnsupportedMethods() throws IOException {
        server = ServerUtils.httpsServerForTest().start();
        try (Response resp = call(request(server.uri()).method("COFFEE", null))) {
            assertThat(resp.code(), is(405));
            assertThat(resp.body().string(), containsString("405 Method Not Allowed"));
        }
        assertThat(server.stats().invalidHttpRequests(), is(1L));
    }

    @Test
    public void absoluteURIsInTheRequestLineAreFine() throws Exception {
        server = httpServer()
            .addHandler((req, resp) -> {
                resp.write(req.uri().toString() + " path=" + req.uri().getPath() + " and query=" + req.query().get("query"));
                return true;
            })
            .start();
        runUriTest(server.uri().resolve("/blah%20blah?query=ha%20ha").toString(), server.uri().resolve("/blah%20blah?query=ha%20ha") + " path=/blah blah and query=ha ha");
    }

    @Test
    public void absoluteURIsWithoutPathsInTheRequestLineAreFine() throws Exception {
        server = httpServer()
            .addHandler((req, resp) -> {
                resp.write(req.uri().toString() + " path=" + req.uri().getPath() + " and query=" + req.query().get("query"));
                return true;
            })
            .start();
        runUriTest("localhost:443", server.uri().resolve("/") + " path=/ and query=null");
    }

    @Test
    public void doubleSlashesAreIgnored() throws Exception {
        server = httpServer().start();
        String body = runUriTest("//hello/wor%20ld?hello=wo%20rld&two=three", "");
        assertThat(body, startsWith("HTTP/1.1 302 Found"));
        assertThat(body, containsString("location: /hello/wor%20ld?hello=wo%20rld&two=three"));
    }

    private String runUriTest(String requestLineUri, String expectedBody) throws InterruptedException, IOException {
        try (RawClient client = RawClient.create(server.uri())
            .sendStartLine("GET", requestLineUri)
            .sendHeader("Host", server.uri().getAuthority())
            .endHeaders()
            .flushRequest()) {
            while (client.responseString().isEmpty()) {
                Thread.sleep(100);
            }
            String body = client.responseString();
            assertThat(body, endsWith("\r\n\r\n" + expectedBody));
            return body;
        }
    }

    @Test
    public void invalidRequestPathsReturn400() throws IOException, InterruptedException {
        server = httpServer()
            .addHandler((req, resp) -> {
                resp.write(req.uri().toString() + " path=" + req.uri().getPath() + " and query=" + req.query().get("query"));
                return true;
            })
            .start();
        try (RawClient client = RawClient.create(server.uri())
            .sendStartLine("GET", "/<blah>/")
            .sendHeader("Host", server.uri().getAuthority())
            .endHeaders()
            .flushRequest()) {
            while (client.responseString().isEmpty()) {
                Thread.sleep(100);
            }
            String body = client.responseString();
            assertThat(body, startsWith("HTTP/1.1 400 Bad Request"));
            assertThat(body, endsWith("Invalid request URL"));
        }
        assertThat(server.stats().invalidHttpRequests(), is(1L));
    }

    @Test
    public void returns400IfNoHostHeaderPresent() throws Exception {
        server = httpServer().start();
        try (RawClient rawClient = RawClient.create(server.uri())) {
            rawClient.sendStartLine("GET", "/");
            rawClient.sendHeader("NotHost", "Blah");
            rawClient.endHeaders();
            rawClient.flushRequest();
            assertEventually(rawClient::responseString, startsWith("HTTP/1.1 400 Bad Request\r\n"));
        }
        assertEventually(() -> server.stats().completedRequests(), is(1L));
    }

    @Test
    public void nonUTF8IsSupported() throws IOException {
        File warAndPeaceInRussian = FileUtils.warAndPeaceInRussian();

        server = ServerUtils.httpsServerForTest()
            .addHandler((req, resp) -> {
                resp.contentType(req.headers().contentType().toString());
                String body = req.readBodyAsString();
                resp.write(body);
                return true;
            })
            .start();

        try (Response resp = call(request(server.uri())
            .post(RequestBody.create(warAndPeaceInRussian, okhttp3.MediaType.get("text/plain; charset=ISO-8859-5")))
        )) {
            assertThat(resp.header("Content-Type"), is("text/plain;charset=ISO-8859-5"));
            String body = resp.body().string();
            assertThat(body, containsString("ЧАСТЬ ПЕРВАЯ."));
        }
    }

    @Test
    public void requestProtocolIsAvailable() throws IOException {
        server = ServerUtils.httpsServerForTest()
            .addHandler((req, resp) -> {
                resp.write(req.connection().protocol());
                return true;
            })
            .start();

        try (Response resp = call(request(server.uri()))) {
            String body = resp.body().string();
            assertThat(body, is(oneOf("HTTP/1.1", "HTTP/2")));
        }
    }

    @Test
    public void zeroBytesCanBeWrittenToTheResponse() throws IOException {
        server = ServerUtils.httpsServerForTest()
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.contentType("text/plain");
                AsyncHandle handle = request.handleAsync();
                handle.write(ByteBuffer.allocateDirect(0));
                handle.write(ByteBuffer.allocateDirect(0));
                handle.write(Mutils.toByteBuffer("Hello"));
                handle.write(ByteBuffer.allocateDirect(0));
                handle.write(ByteBuffer.allocateDirect(0));
                handle.complete();
            })
            .start();
        try (Response resp = call(request(server.uri()))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.header("Content-Type"), is("text/plain"));
            assertThat(resp.body().string(), is("Hello"));
        }
    }

    @Test
    public void idleTimeoutCanBeConfiguredAndConnectionIsClosedWhenBreachedOr408() throws Exception {
        server = ServerUtils.httpsServerForTest()
            .withRequestTimeout(200, TimeUnit.MILLISECONDS)
            .addHandler(Method.POST, "/", (request, response, pathParams) -> {
                String text = request.readBodyAsString();
                response.sendChunk(text);
            })
            .start();
        RequestBody slowBodhy = new RequestBody() {
            @Override
            public MediaType contentType() {
                return MediaType.get("text/plain");
            }

            @Override
            public void writeTo(BufferedSink bufferedSink) throws IOException {
                bufferedSink.writeUtf8("Hello");
                bufferedSink.flush();
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        };
        try (Response resp = call(request(server.uri()).post(slowBodhy))) {
            assertThat(resp.code(), equalTo(408));
            assertThat("Actual headers: " + resp.headers() + "\nBody: " + resp.body().string(),
                resp.headers("connection"), contains("close"));
        } catch (UncheckedIOException se) {
            assertThat(se.getCause(), instanceOf(SocketException.class));
        }
    }

    @Test
    public void idleTimeoutCanBeConfiguredAndConnectionClosedEvenIfNotAlreadyStarted() throws Exception {
        CompletableFuture<Throwable> exceptionFromServer = new CompletableFuture<>();
        server = ServerUtils.httpsServerForTest()
            .withIdleTimeout(50, TimeUnit.MILLISECONDS)
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                Thread.sleep(500);
                try {
                    response.write("Hmmm");
                } catch (Throwable e) {
                    exceptionFromServer.complete(e);
                }
            })
            .start();
        try (Response resp = call(request(server.uri()))) {
            Assertions.fail("Should not succeed but got " + resp.code());
        } catch (RuntimeException re) {
            if (re.getCause() instanceof StreamResetException) {
                assertThat(((StreamResetException) re.getCause()).errorCode, is(ErrorCode.INTERNAL_ERROR));
            } else {
                // expected on HTTP1
                assertThat(re.getCause(), instanceOf(IOException.class));
            }
        }
        assertThat(exceptionFromServer.get(20, TimeUnit.SECONDS), instanceOf(Exception.class));
    }


    @Test
    public void idleTimeoutCanBeConfiguredAndTimeoutHappensWhenItDoes() throws Exception {
        server = MuServerBuilder.httpServer()
            .withIdleTimeout(50, TimeUnit.MILLISECONDS)
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.write("Hi");
            })
            .start();
        RawClient client = RawClient.create(server.uri())
            .sendStartLine("GET", "/")
            .sendHeader("host", server.uri().getAuthority())
            .endHeaders().flushRequest();

        assertEventually(client::isConnected, equalTo(false));
    }

    @Test
    public void unclosedOutputStreamsGetFlushed() throws IOException {
        server = ServerUtils.httpsServerForTest()
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.contentType("text/plain;charset=utf-8");
                response.headers().set(HeaderNames.CONTENT_LENGTH, 5);
                response.outputStream(8192).write("Hello".getBytes(StandardCharsets.UTF_8));
            })
            .start();
        try (Response resp = call(request(server.uri()))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), equalTo("Hello"));
            assertThat(resp.header("content-type"), is("text/plain;charset=utf-8"));
            assertThat(resp.header("transfer-encoding"), is(nullValue()));
            assertThat(resp.header("content-length"), is("5"));
        }
    }

    @Test
    public void unclosedWriterGetFlushed() throws IOException {
        server = ServerUtils.httpsServerForTest()
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.contentType("text/plain;charset=utf-8");
                response.headers().set(HeaderNames.CONTENT_LENGTH, 5);
                response.writer().write("Hello");
            })
            .start();
        try (Response resp = call(request(server.uri()))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), equalTo("Hello"));
            assertThat(resp.header("content-type"), is("text/plain;charset=utf-8"));
            assertThat(resp.header("transfer-encoding"), is(nullValue()));
            assertThat(resp.header("content-length"), is("5"));
        }
    }

    @Test
    public void idleTimeoutCanBeConfiguredAndConnectionClosedIfAlreadyStarted() throws Exception {
        CompletableFuture<Throwable> exceptionFromServer = new CompletableFuture<>();
        CompletableFuture<ResponseInfo> received = new CompletableFuture<>();
        server = ServerUtils.httpsServerForTest()
            .withIdleTimeout(50, TimeUnit.MILLISECONDS)
            .addResponseCompleteListener(received::complete)
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.sendChunk("Hi");
                try {
                    while (true) {
                        Thread.sleep(120);
                        response.sendChunk("Hi");
                    }
                } catch (Throwable e) {
                    exceptionFromServer.complete(e);
                }
            })
            .start();
        try (Response resp = call(request(server.uri()))) {
            assertThat(resp.code(), is(200));
            resp.body().string();
            Assertions.fail("Body should not be readable");
        } catch (StreamResetException e) {
            assertThat(e.errorCode, is(ErrorCode.INTERNAL_ERROR));
        } catch (IOException e) {
            // expected on HTTP1
        }
        assertThat(exceptionFromServer.get(10, TimeUnit.SECONDS), instanceOf(Exception.class));
        ResponseInfo info = received.get(10, TimeUnit.SECONDS);
        assertThat(info, notNullValue());
        assertThat(info.completedSuccessfully(), is(false));
        assertThat(info.duration(), greaterThan(-1L));

    }

    @ParameterizedTest
    @ValueSource(strings = {"http", "https", "h2"})
    public void aifRequestsCannotBeSubmittedToTheExecutorTheyAreRejectedWithA503(String protocol) throws Exception {
        CountDownLatch firstRequestStartedLatch = new CountDownLatch(1);
        CountDownLatch thirdRequestFinishedLatch = new CountDownLatch(1);

        MuServerBuilder builder;
        switch (protocol) {
            case "http": builder = MuServerBuilder.httpServer(); break;
            case "https": builder = MuServerBuilder.httpsServer().withHttp2Config(Http2ConfigBuilder.http2Disabled()); break;
            case "h2": builder = MuServerBuilder.httpsServer(); break;
            default: throw new IllegalArgumentException("Unsupported protocol: " + protocol);
        }
        server = builder
            .withHandlerExecutor(new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>() {
                    public synchronized boolean offer(@NonNull Runnable runnable) {
                        // The first request will go to the waiting thread; only the second will be offered
                        return false;
                    }
                }))
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                // wait until the third request has been made, which should fail due to 2 requests being in progress
                String count = request.query().get("count");
                response.sendChunk("First bit of " + count);
                firstRequestStartedLatch.countDown();
                thirdRequestFinishedLatch.await();
                response.sendChunk(" and second bit of " + count);
            })
            .start();
        List<String> responses = new CopyOnWriteArrayList<>();
        ExecutorService executor = Executors.newCachedThreadPool();
        CountDownLatch responseFinishedLatch = new CountDownLatch(1);
        executor.submit(() -> {
            try (Response resp = call(request(server.uri().resolve("/?count=first")))) {
                responses.add(resp.body().string());
            } catch (Throwable t) {
                responses.add(t.getMessage());
            }
            responseFinishedLatch.countDown();
        });
        MuAssert.assertNotTimedOut("firstRequestStartedLatch", firstRequestStartedLatch);
        try (Response resp = call(request(server.uri().resolve("/?count=last")))) {
            assertThat(resp.code(), is(503));
            assertThat(resp.body().string(), containsString("503 Service Unavailable"));
        }
        thirdRequestFinishedLatch.countDown();
        MuAssert.assertNotTimedOut("responseLatch", responseFinishedLatch);
        assertThat(responses, containsInAnyOrder("First bit of first and second bit of first"));
        assertEventually(() -> server.stats().completedRequests(), is(1L));
        assertThat(server.stats().rejectedDueToOverload(), is(1L));
        assertThat(executor.shutdownNow(), hasSize(0));
    }

    @Test
    public void versionIsAvailable() {
        assertThat(MuServer.artifactVersion(), equalTo("0.x"));
    }

    @AfterEach
    public void stopIt() {
        scaffolding.MuAssert.stopAndCheck(server);
    }
}