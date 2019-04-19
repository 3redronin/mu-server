package io.muserver;

import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.*;
import scaffolding.RawClient;

import java.io.File;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.muserver.MuServerBuilder.*;
import static java.util.Arrays.asList;
import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
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
        server = httpServer().start();
        try (Response resp = call(request().url(server.httpUri().toString()))) {
            assertThat(resp.code(), is(404));
        }
    }

    @Test
    public void unhandledExceptionsResultIn500sIfNoResponseSent() {
        server = httpServer()
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
        server = httpServer()
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
        server = httpServer()
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

        server = httpServer()
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

        for (String host : asList("127.0.0.1", "localhost")) {
            MuServer server = httpServer()
                .withInterface(host)
                .addHandler(Method.GET, "/", (req, resp, pp) -> resp.write("Hello"))
                .start();
            try (Response ignored = call(request().url("http://" + hostname + ":" + server.uri().getPort()))) {
                Assert.fail("Should have failed to call");
            } catch (RuntimeException rex) {
                assertThat(rex.getCause(), instanceOf(ConnectException.class));
            } finally {
                server.stop();
            }
        }
    }

    @Test
    public void ifBoundToLocalhostThenLoopbackAddressIsUsed() throws IOException {
        Assume.assumeNotNull(hostname);

        for (String host : asList("127.0.0.1", "localhost")) {
            MuServer server = httpServer()
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
        server = httpServer()
            .withInterface("0.0.0.0")
            .addHandler(Method.GET, "/", (req, resp, pp) -> resp.write("Hello from " + server.address().getHostString()))
            .start();
        try (Response resp = call(request().url("http://" + hostname + ":" + server.uri().getPort()))) {
            assertThat(resp.body().string(), startsWith("Hello from 0"));
        }
    }

    @Test
    public void ifBoundToHostnameThenExternalAccessIsPossible() throws IOException {
        Assume.assumeNotNull(hostname);
        server = httpServer()
            .withInterface(hostname)
            .addHandler(Method.GET, "/", (req, resp, pp) -> resp.write("Hello from " + server.uri().getHost()))
            .start();
        try (Response resp = call(request().url("http://" + hostname + ":" + server.uri().getPort()))) {
            assertThat(resp.body().string(), equalTo("Hello from " + hostname));
        }
    }

    @Test
    public void theClientIpAddressOrSomethingLikeThatIsAvailable() throws IOException {
        server = httpServer()
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
        server = httpServer().start();
        try (Response resp = call(request().method("COFFEE", null).url(server.uri().toString()))) {
            assertThat(resp.code(), is(405));
            assertThat(resp.body().string(), containsString("405 Method Not Allowed"));
        }
    }

    @Test
    public void returns400IfNoHostHeaderPresent() throws Exception {
        server = httpServer().start();
        RawClient rawClient = RawClient.create(server.uri());
        rawClient.sendStartLine("GET", "/");
        rawClient.sendHeader("NotHost", "Blah");
        rawClient.endHeaders();
        rawClient.flushRequest();

        while (!rawClient.responseString().contains("\n")) {
            Thread.sleep(10);
        }
        assertThat(rawClient.responseString(), startsWith("HTTP/1.1 400 Bad Request"));
    }

    @Test
    public void nonUTF8IsSupported() throws IOException {
        File warAndPeaceInRussian = new File("src/test/resources/sample-static/war-and-peace-in-ISO-8859-5.txt");
        assertThat("Couldn't find " + Mutils.fullPath(warAndPeaceInRussian), warAndPeaceInRussian.isFile(), is(true));

        server = httpsServer()
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
    public void zeroBytesCanBeWrittenToTheResponse() throws IOException {
        server = httpsServer()
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
    public void versionIsAvailable() {
        assertThat(MuServer.artifactVersion(), equalTo("0.x"));
    }

    @After
    public void stopIt() {
        scaffolding.MuAssert.stopAndCheck(server);
    }
}