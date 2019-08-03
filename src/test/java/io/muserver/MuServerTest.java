package io.muserver;

import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.internal.http2.ErrorCode;
import okhttp3.internal.http2.StreamResetException;
import org.junit.*;
import scaffolding.MuAssert;
import scaffolding.RawClient;
import scaffolding.ServerUtils;

import java.io.File;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
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

public class MuServerTest {

    private MuServer server;
    private static String hostname;

    @BeforeClass
    public static void setup() {
        try {
            hostname = InetAddress.getLocalHost().getHostName().toLowerCase();
        } catch (Exception e) {
            hostname = null;
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
    public void unhandledExceptionsAreJustLoggedIfResponsesAreAlreadyStarted() {
        server = ServerUtils.httpsServerForTest()
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.status(200);
                response.writer().print("Hello");
                throw new RuntimeException("I'm the fire starter");
            })
            .start();
        try (Response resp = call(request().url(server.uri().toString()))) {
            assertThat(resp.code(), is(200));
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
    public void ifBoundToLocalhostThenExternalAccessIsNotPossible() {
        Assume.assumeNotNull(hostname);

            MuServer server = ServerUtils.httpsServerForTest()
                .withInterface("localhost")
                .addHandler(Method.GET, "/", (req, resp, pp) -> resp.write("Hello"))
                .start();
            try (Response ignored = call(request().url("https://" + hostname + ":" + server.uri().getPort()))) {
                Assert.fail("Should have failed to call " + hostname + " when bound to " + "localhost" + " via " + server.uri());
            } catch (RuntimeException rex) {
                assertThat(rex.getCause(), instanceOf(ConnectException.class));
            } finally {
                server.stop();
            }
    }

    @Test
    public void ifBoundToLocalhostThenLoopbackAddressIsUsed() throws IOException {
        Assume.assumeNotNull(hostname);

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
        Assume.assumeNotNull(hostname);
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
        Assume.assumeNotNull(hostname);
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
            Assert.fail("No exception thrown");
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

    private void runUriTest(String requestLineUri, String expectedBody) throws InterruptedException, IOException {
        try (RawClient client = RawClient.create(server.uri())
            .sendStartLine("GET", requestLineUri)
            .sendHeader("Host", server.uri().getAuthority())
            .endHeaders()
            .flushRequest()) {
            while (client.responseString().isEmpty()) {
                Thread.sleep(100);
            }
            assertThat(client.responseString(), endsWith("\r\n\r\n" + expectedBody));
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
            assertThat(body, endsWith("400 Bad Request"));
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

            while (!rawClient.responseString().contains("\n")) {
                Thread.sleep(10);
            }
            assertThat(rawClient.responseString(), startsWith("HTTP/1.1 400 Bad Request"));
        }
        assertThat(server.stats().invalidHttpRequests(), is(1L));
    }

    @Test
    public void nonUTF8IsSupported() throws IOException {
        File warAndPeaceInRussian = new File("src/test/resources/sample-static/war-and-peace-in-ISO-8859-5.txt");
        assertThat("Couldn't find " + Mutils.fullPath(warAndPeaceInRussian), warAndPeaceInRussian.isFile(), is(true));

        server = ServerUtils.httpsServerForTest()
            .addHandler((req, resp) -> {
                resp.contentType(req.headers().contentType().toString());
                String body = req.readBodyAsString();
                resp.write(body);
                return true;
            })
            .start();

        try (Response resp = call(request(server.uri())
            .post(RequestBody.create(okhttp3.MediaType.get("text/plain; charset=ISO-8859-5"), warAndPeaceInRussian))
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
                resp.write(req.protocol());
                return true;
            })
            .start();

        try (Response resp = call(request(server.uri()))) {
            String body = resp.body().string();
            assertThat(body, isOneOf("HTTP/1.1", "HTTP/2"));
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
    public void idleTimeoutCanBeConfiguredAnd408ReturnedIfResponseNotStarted() throws Exception {
        CompletableFuture<Throwable> exceptionFromServer = new CompletableFuture<>();
        server = ServerUtils.httpsServerForTest()
            .withIdleTimeout(100, TimeUnit.MILLISECONDS)
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                Thread.sleep(125);
                try {
                    response.write("Hmmm");
                } catch (Throwable e) {
                    exceptionFromServer.complete(e);
                }
            })
            .start();
        try (Response resp = call(request(server.uri()))) {
            assertThat(resp.code(), is(408)); // HTTP1
        } catch (Exception e) {
            // HTTP2 will result in a canceled stream
            assertThat(e.getCause(), instanceOf(StreamResetException.class));
            assertThat(((StreamResetException)e.getCause()).errorCode, is(ErrorCode.CANCEL));
        }
        assertThat(exceptionFromServer.get(10, TimeUnit.SECONDS), instanceOf(Exception.class));
    }

    @Test
    public void idleTimeoutCanBeConfiguredAndConnectionClosedIfAlreadyStarted() throws Exception {
        CompletableFuture<Throwable> exceptionFromServer = new CompletableFuture<>();
        server = ServerUtils.httpsServerForTest()
            .withIdleTimeout(200, TimeUnit.MILLISECONDS)
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.sendChunk("Hi");
                try {
                    while (true) {
                        Thread.sleep(210);
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
            Assert.fail("Body should not be readable");
        } catch (StreamResetException e) {
            // HTTP2 will result in a canceled stream
            assertThat(e.errorCode, is(ErrorCode.CANCEL));
        } catch (IOException e) {
            // expected on HTTP1
        }
        assertThat(exceptionFromServer.get(10, TimeUnit.SECONDS), instanceOf(Exception.class));
    }


    @Test(timeout = 30000)
    public void aifRequestsCannotBeSubmittedToTheExecutorTheyAreRejectedWithA503() throws Exception {
        CountDownLatch firstRequestStartedLatch = new CountDownLatch(1);
        CountDownLatch thirdRequestFinishedLatch = new CountDownLatch(1);
        server = ServerUtils.httpsServerForTest()
            .withHandlerExecutor(new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>() {
                    public synchronized boolean offer(Runnable runnable) {
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
            assertThat(resp.body().string(), is("503 Service Unavailable"));
        }
        thirdRequestFinishedLatch.countDown();
        MuAssert.assertNotTimedOut("responseLatch", responseFinishedLatch);
        assertThat(responses, containsInAnyOrder("First bit of first and second bit of first"));
        assertThat(server.stats().rejectedDueToOverload(), is(1L));
        assertThat(executor.shutdownNow(), hasSize(0));
    }


    @Test
    public void versionIsAvailable() {
        assertThat(MuServer.artifactVersion(), equalTo("0.x"));
    }

    @After
    public void stopIt() {
        scaffolding.MuAssert.stopAndCheck(server);
    }
}