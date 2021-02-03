package io.muserver;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scaffolding.*;

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

    @Test(timeout = 60000)
    public void canBeAlertedWhenResponseCompletesWithFailure() throws Exception {
        CompletableFuture<ResponseInfo> received = new CompletableFuture<>();
        server = ServerUtils.httpsServerForTest()
            .withRequestTimeout(2000, TimeUnit.MILLISECONDS)
            .addResponseCompleteListener(received::complete)
            .addHandler(null, "/blah", (req, resp, pp) -> {
                while (true) {
                    resp.sendChunk(StringUtils.randomAsciiStringOfLength(640000));
                    Thread.sleep(50);
                }
            })
            .start();
        OkHttpClient client = ClientUtils.client.newBuilder().readTimeout(3000, TimeUnit.MILLISECONDS).build();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                for (Call runningCall : client.dispatcher().runningCalls()) {
                    log.info("Cancelling...");
                    runningCall.cancel();
                    log.info("Cancelled");
                }
            }
        }).start();
        try (Response resp = client.newCall(request(server.uri().resolve("/blah"))
            .post(new SlowBodySender(3, 10000))
            .build()).execute()) {
            resp.body().bytes();
            Assert.fail("Why");
        } catch (Exception ex) {
            // expected due to timeout
            log.info("Got ex as expected: " + ex.getMessage());
        }

        ResponseInfo info = received.get(10, TimeUnit.SECONDS);
        assertThat(info, notNullValue());
        assertThat(info.completedSuccessfully(), is(false)); // this is expected. the server may not know
        // so
        // if the request body isn't complete, server readIdleTimeout should fire
        // if the client disconnects while still writing, it should detect that (so somewhere call
        // but if the full request body is sent, the server doesn't know if the client has disconnected or not
        assertThat(info.duration(), greaterThan(-1L));
    }


    @After
    public void stop() {
        MuAssert.stopAndCheck(server);
    }

}
