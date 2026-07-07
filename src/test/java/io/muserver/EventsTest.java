package io.muserver;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.After;
import org.junit.Test;
import scaffolding.Http1Client;
import scaffolding.MuAssert;
import scaffolding.ServerUtils;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;
import static scaffolding.MuAssert.assertEventually;
import static scaffolding.StringUtils.randomAsciiStringOfLength;

public class EventsTest {

    private MuServer server;

    @Test
    public void canBeAlertedWhenResponseCompletes() throws Exception {
        CompletableFuture<ResponseInfo> received = new CompletableFuture<>();
        server = ServerUtils.httpsServerForTest()
            .addResponseCompleteListener(received::complete)
            .addHandler(Method.GET, "/blah", (req, resp, pp) -> {
                resp.headers().set("Hello", "World");
                resp.status(400);
                resp.write("Hey there");
            })
            .start();
        try (Response resp = call(request(server.uri().resolve("/blah")))) {
            assertThat(resp.code(), is(400));
            resp.body().string();
        }
        ResponseInfo info = received.get(10, TimeUnit.SECONDS);
        assertThat(info, notNullValue());
        assertThat(info.completedSuccessfully(), is(true));
        assertThat(info.duration(), greaterThan(-1L));
        assertThat(info.response().status(), is(400));
        assertThat(info.response().headers().get("Hello"), is("World"));
        assertThat(info.request().uri(), equalTo(server.uri().resolve("/blah")));
    }

    @Test
    public void completeListenerCallbackTest_GET() {

        AtomicReference<String> completeStateSnapshot = new AtomicReference<>("");

        server = ServerUtils.httpsServerForTest()
            .addResponseCompleteListener(info -> {
                completeStateSnapshot.set(String.valueOf(info.completedSuccessfully()));
            })
            .addHandler(Method.GET, "/blah", (req, resp, pp) -> {
                resp.status(200);
                resp.write("Hey there");
            })
            .start();
        Request.Builder request = request(server.uri().resolve("/blah"))
            .get();
        try (Response resp = call(request)) {
            assertThat(resp.code(), is(200));
        }

        assertEventually(completeStateSnapshot::get, is("true"));
    }

    @Test
    public void rejectListenerIsCalledWhenHeadersAreTooLarge431_OverHttp1() throws Exception {
        CompletableFuture<RejectedRequest> rejected = new CompletableFuture<>();
        AtomicBoolean completeListenerCalled = new AtomicBoolean(false);
        server = ServerUtils.httpsServerForTest()
            .withHttp2Config(Http2ConfigBuilder.http2Config().enabled(false))
            .withMaxHeadersSize(1024)
            .addRequestRejectListener(rejected::complete)
            .addResponseCompleteListener(info -> completeListenerCalled.set(true))
            .addHandler(Method.GET, "/blah", (req, resp, pp) -> resp.write("Hello"))
            .start();

        try (Http1Client client = Http1Client.connect(server)) {
            client.writeRequestLine(Method.GET, "/blah")
                .writeHeader("X-Big", randomAsciiStringOfLength(2000))
                .endHeaders()
                .flush();
            assertThat(client.readLine(), containsString("431"));
        }

        RejectedRequest info = rejected.get(10, TimeUnit.SECONDS);
        assertThat(info.status(), is(431));
        assertThat(info.reason(), is("431 Request Header Fields Too Large"));
        assertThat(info.method(), is(Optional.of("GET")));
        assertThat(info.uri().get().getPath(), is("/blah"));
        assertThat(info.connection(), notNullValue());
        assertThat(info.connection().protocol(), is("HTTP/1.1"));
        assertThat(info.connection().remoteAddress(), notNullValue());
        assertThat("The response complete listener should not fire for a rejected request",
            completeListenerCalled.get(), is(false));
    }

    @Test
    public void rejectListenerIsCalledWhenBodyIsTooLarge413_OverHttp2() throws Exception {
        CompletableFuture<RejectedRequest> rejected = new CompletableFuture<>();
        server = ServerUtils.httpsServerForTest()
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .withMaxRequestSize(100)
            .addRequestRejectListener(rejected::complete)
            .addHandler(Method.POST, "/blah", (req, resp, pp) -> resp.write("Hello"))
            .start();

        RequestBody body = RequestBody.create(randomAsciiStringOfLength(1000), MediaType.get("text/plain"));
        try (Response resp = call(request(server.uri().resolve("/blah")).post(body))) {
            assertThat(resp.code(), is(413));
        }

        RejectedRequest info = rejected.get(10, TimeUnit.SECONDS);
        assertThat(info.status(), is(413));
        assertThat(info.reason(), is("413 Payload Too Large"));
        assertThat(info.method(), is(Optional.of("POST")));
        assertThat(info.uri().get().getPath(), is("/blah"));
        assertThat(info.connection().protocol(), is("HTTP/2"));
    }

    @Test
    public void rejectListenerIsCalledWhenBodyIsTooLarge413_OverHttp1() throws Exception {
        CompletableFuture<RejectedRequest> rejected = new CompletableFuture<>();
        server = ServerUtils.httpsServerForTest()
            .withHttp2Config(Http2ConfigBuilder.http2Config().enabled(false))
            .withMaxRequestSize(100)
            .addRequestRejectListener(rejected::complete)
            .addHandler(Method.POST, "/blah", (req, resp, pp) -> resp.write("Hello"))
            .start();

        RequestBody body = RequestBody.create(randomAsciiStringOfLength(1000), MediaType.get("text/plain"));
        try (Response resp = call(request(server.uri().resolve("/blah")).post(body))) {
            assertThat(resp.code(), is(413));
        }

        RejectedRequest info = rejected.get(10, TimeUnit.SECONDS);
        assertThat(info.status(), is(413));
        assertThat(info.reason(), is("413 Payload Too Large"));
        assertThat(info.method(), is(Optional.of("POST")));
        assertThat(info.uri().get().getPath(), is("/blah"));
        assertThat(info.connection().protocol(), is("HTTP/1.1"));
    }

    @Test
    public void rejectListenerIsNotCalledForSuccessfulRequests() {
        AtomicBoolean rejectListenerCalled = new AtomicBoolean(false);
        server = ServerUtils.httpsServerForTest()
            .addRequestRejectListener(info -> rejectListenerCalled.set(true))
            .addHandler(Method.GET, "/blah", (req, resp, pp) -> resp.write("Hello"))
            .start();
        try (Response resp = call(request(server.uri().resolve("/blah")))) {
            assertThat(resp.code(), is(200));
        }
        assertThat(rejectListenerCalled.get(), is(false));
    }

    @After
    public void stop() {
        MuAssert.stopAndCheck(server);
    }

}
