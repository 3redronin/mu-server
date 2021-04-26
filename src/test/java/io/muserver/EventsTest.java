package io.muserver;

import okhttp3.Response;
import org.junit.After;
import org.junit.Test;
import scaffolding.MuAssert;
import scaffolding.ServerUtils;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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
        assertEventually(info::completedSuccessfully, is(true));
        assertThat(info.duration(), greaterThan(-1L));
        assertThat(info.response().status(), is(400));
        assertThat(info.response().headers().get("Hello"), is("World"));
        assertThat(info.request().uri(), equalTo(server.uri().resolve("/blah")));
    }

    @After
    public void stop() {
        MuAssert.stopAndCheck(server);
    }

}
