package io.muserver;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Test;
import scaffolding.ClientUtils;
import scaffolding.MuAssert;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.muserver.MuServerBuilder.httpServer;
import static org.hamcrest.MatcherAssert.assertThat;
import static scaffolding.ClientUtils.request;

public class FlakyClientTest {

    private MuServer server;

    @Test
    public void canHandleStuffWithoutBreaking() throws InterruptedException {
        server = httpServer()
            .addHandler(Method.GET, "/full", (req, resp, pp) -> {
                resp.write("A full length message");
            })
            .addHandler(Method.GET, "/chunks", (req, resp, pp) -> {
                Thread.sleep(4);
                resp.sendChunk("Chunk one");
                resp.sendChunk("Chunk two");
            })
            .start();

        OkHttpClient shortReader = ClientUtils.client.newBuilder().readTimeout(10, TimeUnit.MILLISECONDS).build();

        int calls = 200;
        CountDownLatch latch = new CountDownLatch(calls * 2);
        for (int i = 0; i < calls; i++) {
            Callback responseCallback = new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    latch.countDown();

                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    latch.countDown();
                    response.close();

                }
            };
            shortReader.newCall(request().url(server.uri().resolve("/full").toString()).build()).enqueue(responseCallback);
            shortReader.newCall(request().url(server.uri().resolve("/chunks").toString()).build()).enqueue(responseCallback);
        }

        assertThat(latch.await(2, TimeUnit.MINUTES), Matchers.is(true));

    }


    @After
    public void stopIt() {
        MuAssert.stopAndCheck(server);
    }

}
