package io.muserver;

import okhttp3.OkHttpClient;
import okhttp3.Response;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scaffolding.ClientUtils;
import scaffolding.MuAssert;
import scaffolding.ServerUtils;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

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
        }
        ResponseInfo info = received.get(10, TimeUnit.SECONDS);
        assertThat(info, notNullValue());
        assertThat(info.duration(), greaterThan(-1L));
        assertThat(info.completedSuccessfully(), is(true));
        assertThat(info.response().status(), is(400));
        assertThat(info.response().headers().get("Hello"), is("World"));
        assertThat(info.request().uri(), equalTo(server.uri().resolve("/blah")));
    }

    private static final Logger log = LoggerFactory.getLogger(EventsTest.class);
    @Test
    public void canBeAlertedWhenResponseCompletesWithFailure() throws Exception {
        CompletableFuture<ResponseInfo> received = new CompletableFuture<>();
        server = ServerUtils.httpsServerForTest()
            .addResponseCompleteListener(received::complete)
            .addHandler(Method.GET, "/blah", (req, resp, pp) -> {
                Thread.sleep(500);
            })
            .start();
        OkHttpClient client = ClientUtils.client.newBuilder().readTimeout(250, TimeUnit.MILLISECONDS).build();
        try (Response resp = client.newCall(request(server.uri().resolve("/blah")).build()).execute()) {
            resp.body().bytes();
            Assert.fail("Why");
        } catch (Exception ex) {
            // expected due to timeout
            log.info("Got ex as expected: " + ex.getMessage());
        }

        ResponseInfo info = received.get(10, TimeUnit.SECONDS);
        assertThat(info, notNullValue());
        assertThat(info.completedSuccessfully(), is(false));
        assertThat(info.duration(), greaterThan(-1L));
    }


    @After
    public void stop() {
        MuAssert.stopAndCheck(server);
    }

}
