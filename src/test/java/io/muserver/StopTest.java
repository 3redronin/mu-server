package io.muserver;

import okhttp3.Response;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThrows;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class StopTest {

    private static final Logger log = LoggerFactory.getLogger(StopTest.class);

    @Test
    public void gracefulShutdown() throws InterruptedException {

        CountDownLatch serverReceivedLatch = new CountDownLatch(1);
        CountDownLatch clientReceivedLatch = new CountDownLatch(1);
        AtomicInteger clientReceivedStatus = new AtomicInteger();

        MuServer server = MuServerBuilder
            .httpServer()
            .addHandler((request, response) -> {
                log.info("received request {}", request);
                serverReceivedLatch.countDown();

                AsyncHandle asyncHandle = request.handleAsync();
                asyncHandle.addResponseCompleteHandler(info -> log.info("request completed {}", info));

                Thread.sleep(2000L);
                response.status(200);
                asyncHandle.write(Mutils.toByteBuffer("Hello"));
                asyncHandle.complete();

                return true;
            })
            .start();



        new Thread(() -> {
            try (Response resp = call(request().url(server.uri().toString()))) {
                clientReceivedStatus.set(resp.code());
                clientReceivedLatch.countDown();
            }
        }).start();

        assertThat(serverReceivedLatch.await(2, TimeUnit.SECONDS), is(true));

        new Thread(() -> server.stop(2, TimeUnit.SECONDS)).start();

        // new request should fail
        Thread.sleep(200L);
        assertThrows(Exception.class, () -> {
            try (Response resp = call(request().url(server.uri().toString()))) {
                assertThat(resp.code(), is(200));
            }
        });

        // the previous in flight request should completed
        assertThat(clientReceivedLatch.await(2, TimeUnit.SECONDS), is(true));
        assertThat(clientReceivedStatus.get(), is(200));
    }
}
