package io.muserver;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import org.junit.After;
import org.junit.Test;
import scaffolding.ClientUtils;
import scaffolding.MuAssert;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.muserver.MuServerBuilder.httpServer;
import static io.muserver.MuServerBuilder.httpsServer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class FlakyClientTest {

    private MuServer server;

    @Test
    public void canHandleStuffWithoutBreakingOverHttp() throws InterruptedException, IOException {
        runTest(httpServer());
    }

    @Test
    public void canHandleStuffWithoutBreakingOverHttps() throws InterruptedException, IOException {
        runTest(httpsServer());
    }

    protected void runTest(MuServerBuilder muServerBuilder) throws InterruptedException, IOException {
        server = muServerBuilder
            .addHandler(Method.GET, "/full", (req, resp, pp) -> {
                try {
                    resp.write("A full length message");
                } catch (Exception ignored) {
                }
            })
            .addHandler(Method.GET, "/chunks", (req, resp, pp) -> {
                Thread.sleep(4);
                try {
                    resp.sendChunk("Chunk one");
                    resp.sendChunk("Chunk two");
                } catch (Exception ignored) {
                }
            })
            .start();

        OkHttpClient shortReader = ClientUtils.client.newBuilder()
            .readTimeout(10, TimeUnit.MILLISECONDS)
            .build();

        int calls = 200;
        CountDownLatch latch = new CountDownLatch(calls * 2);
        for (int i = 0; i < calls; i++) {
            Callback responseCallback = new Callback() {
                public void onFailure(Call call, IOException e) {
                    latch.countDown();
                }
                public void onResponse(Call call, Response response) {
                    latch.countDown();
                    response.close();

                }
            };
            shortReader.newCall(request(server.uri().resolve("/full")).build()).enqueue(responseCallback);
            shortReader.newCall(request(server.uri().resolve("/chunks")).build()).enqueue(responseCallback);
        }

        assertThat(latch.await(2, TimeUnit.MINUTES), is(true));

        try (Response resp = call(request(server.uri().resolve("/full")))) {
            assertThat(resp.body().string(), is("A full length message"));
        }
        try (Response resp = call(request(server.uri().resolve("/chunks")))) {
            assertThat(resp.body().string(), is("Chunk oneChunk two"));
        }
    }


    @After
    public void stopIt() {
        MuAssert.stopAndCheck(server);
    }

}
