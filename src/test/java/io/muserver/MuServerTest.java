package io.muserver;

import okhttp3.RequestBody;
import okhttp3.Response;
import org.hamcrest.Matchers;
import org.junit.*;
import scaffolding.RawClient;

import java.io.File;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.URI;
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
        server = httpsServer().start();
        try (Response resp = call(request().url(server.uri().toString()))) {
            assertThat(resp.code(), is(404));
        }
    }

    @Test
    public void unhandledExceptionsResultIn500sIfNoResponseSent() {
        server = httpsServer()
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
        server = httpsServer()
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
        server = httpsServer()
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

        server = httpsServer()
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
            MuServer server = httpsServer()
                .withInterface(host)
                .addHandler(Method.GET, "/", (req, resp, pp) -> resp.write("Hello"))
                .start();
            try (Response ignored = call(request().url("https://" + hostname + ":" + server.uri().getPort()))) {
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
            MuServer server = httpsServer()
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
        server = httpsServer()
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
        server = httpsServer()
            .withInterface(hostname)
            .addHandler(Method.GET, "/", (req, resp, pp) -> resp.write("Hello from " + server.uri().getHost()))
            .start();
        try (Response resp = call(request().url("https://" + hostname + ":" + server.uri().getPort()))) {
            assertThat(resp.body().string(), equalTo("Hello from " + hostname));
        }
    }

    @Test
    public void theClientIpAddressOrSomethingLikeThatIsAvailable() throws IOException {
        server = httpsServer()
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
        server = httpsServer().start();
        try (Response resp = call(request(server.uri()).method("COFFEE", null))) {
            assertThat(resp.code(), is(405));
            assertThat(resp.body().string(), containsString("405 Method Not Allowed"));
        }
    }

    @Test
    public void absoluteURIsInTheRequestLineAreFine() throws IOException, InterruptedException {
        server = httpServer()
            .addHandler((req, resp) -> {
                resp.write(req.uri().toString() + " path=" + req.uri().getPath() + " and query=" + req.query().get("query"));
                return true;
            })
            .start();
        URI target = server.uri().resolve("/blah%20blah?query=ha%20ha");
        try (RawClient client = RawClient.create(server.uri())
            .sendStartLine("GET", target.toString())
            .sendHeader("Host", server.uri().getAuthority())
            .endHeaders()
            .flushRequest()) {
            while (client.responseString().isEmpty()) {
                Thread.sleep(100);
            }
            assertThat(client.responseString(), endsWith("\r\n\r\n" + target + " path=/blah blah and query=ha ha"));
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
    public void requestProtocolIsAvailable() throws IOException {
        server = httpsServer()
            .addHandler((req, resp) -> {
                resp.write(req.protocol());
                return true;
            })
            .start();

        try (Response resp = call(request(server.uri()))) {
            String body = resp.body().string();
            assertThat(body, Matchers.isOneOf("HTTP/1.1", "HTTP/2"));
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