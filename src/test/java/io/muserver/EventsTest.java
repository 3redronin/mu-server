package io.muserver;

import okhttp3.Request;
import okhttp3.Response;
import org.junit.After;
import org.junit.Test;
import scaffolding.MuAssert;
import scaffolding.ServerUtils;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;
import static scaffolding.MuAssert.assertEventually;

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

    @After
    public void stop() {
        MuAssert.stopAndCheck(server);
    }

}
