package io.muserver;

import org.junit.After;
import org.junit.Test;
import scaffolding.ClientUtils;
import scaffolding.SseClient;
import scaffolding.TestSseClient;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static io.muserver.MuServerBuilder.httpServer;
import static io.muserver.MuServerBuilder.httpsServer;
import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.request;

public class AsyncSsePublisherTest {

    private MuServer server;
    private final SseClient.OkSse sseClient = new SseClient.OkSse(ClientUtils.client);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final TestSseClient listener = new TestSseClient();


    @Test
    public void canCall() throws InterruptedException {
        String multilineJson = "{\n" +
            "    \"value2\": \"Something \\n more\",\n" +
            "    \"value1\": \"Something\"\n" +
            "}";

        server = httpsServer()
            .addHandler(Method.GET, "/streamer", (request, response, pathParams) -> {

                AsyncSsePublisher ssePublisher = AsyncSsePublisher.start(request, response);
                ssePublisher.sendComment("this is a comment")
                    .thenRunAsync(() -> {
                        ssePublisher.send("Just a message");
                        ssePublisher.send("A message and event", "customevent");
                        ssePublisher.setClientReconnectTime(3, TimeUnit.SECONDS);
                        ssePublisher.send("A message and ID", null, "myid");
                        ssePublisher.send("A message and event and ID", "customevent", "myid");
                        ssePublisher.sendComment("this is a comment 2");
                        ssePublisher.send(multilineJson, null, null);
                        ssePublisher.close();
                    });

            })
            .start();

        try (SseClient.ServerSentEvent ignored = sseClient.newServerSentEvent(request().url(server.uri().resolve("/streamer").toString()).build(), listener)) {
            listener.assertListenerIsClosed();
        }
        assertThat(listener.receivedMessages, equalTo(asList(
            "open",
            "comment=this is a comment",
            "message=Just a message        event=message        id=null",
            "message=A message and event        event=customevent        id=null",
            "retryTime=3000",
            "message=A message and ID        event=message        id=myid",
            "message=A message and event and ID        event=customevent        id=myid",
            "comment=this is a comment 2",
            "message=" + multilineJson + "        event=message        id=myid",
            "retryError",
            "closed")));
    }


    @Test
    public void sendThrowsAnExceptionIfTheClientDisconnects() throws InterruptedException {
        AtomicReference<Throwable> thrownException = new AtomicReference<>();
        CountDownLatch somethingPublishedLatch = new CountDownLatch(1);
        CountDownLatch exceptionThrownLatch = new CountDownLatch(1);
        server = httpServer()
            .addHandler(Method.GET, "/streamer", (request, response, pathParams) -> {

                AsyncSsePublisher ssePublisher = AsyncSsePublisher.start(request, response);
                executor.submit(() -> {
                    int i = 0;
                    ssePublisher.send("This is pre message") // force at least one message
                        .whenComplete((o, throwable) -> somethingPublishedLatch.countDown());

                    try {
                        somethingPublishedLatch.await(10, TimeUnit.SECONDS);
                        while (thrownException.get() == null) {
                            CompletionStage<?> stage = ssePublisher.send("This is message " + i);
                            stage.whenComplete((o, throwable) -> {
                                if (throwable != null) {
                                    thrownException.set(throwable);
                                    exceptionThrownLatch.countDown();
                                }
                            });
                            Thread.sleep(200);
                            i++;
                        }
                    } catch (InterruptedException ignored) {
                        exceptionThrownLatch.countDown();
                    }
                });

            })
            .start();

        try (SseClient.ServerSentEvent ignored = sseClient.newServerSentEvent(request().url(server.uri().resolve("/streamer").toString()).build(), listener)) {
            assertThat("Timed out waiting for SSE publisher to start", somethingPublishedLatch.await(10, TimeUnit.SECONDS), is(true));
        }
        assertThat("Timed out waiting for SSE publisher to stop", exceptionThrownLatch.await(10, TimeUnit.SECONDS), is(true));
        assertThat(thrownException.get(), is(notNullValue()));

    }


    @After
    public void destroy() {
        scaffolding.MuAssert.stopAndCheck(server);
        listener.cleanup();
    }


}
