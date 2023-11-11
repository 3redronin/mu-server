package io.muserver;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import scaffolding.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

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

        AtomicReference<Throwable> sendError = new AtomicReference<>();
        CountDownLatch responseInfoLatch = new CountDownLatch(1);
        AtomicReference<ResponseInfo> responseInfo = new AtomicReference<>(null);
        server = ServerUtils.httpsServerForTest()
            .addHandler(Method.GET, "/streamer", (request, response, pathParams) -> {

                var ssePublisher = AsyncSsePublisher.start(request, response);
                ssePublisher.setResponseCompleteHandler(info -> {
                    responseInfo.set(info);
                    responseInfoLatch.countDown();
                });
                ssePublisher.sendComment("this is a comment")
                    .thenCompose(ignored -> ssePublisher.send("Just a message"))
                    .thenCompose(ignored -> ssePublisher.send("A message and event", "customevent"))
                    .thenCompose(ignored -> ssePublisher.setClientReconnectTime(3, TimeUnit.SECONDS))
                    .thenCompose(ignored -> ssePublisher.send("A message and ID", null, "myid"))
                    .thenCompose(ignored -> ssePublisher.send("A message and event and ID", "customevent", "myid"))
                    .thenCompose(ignored -> ssePublisher.sendComment("this is a comment 2"))
                    .thenCompose(ignored -> ssePublisher.send(multilineJson, null, null))
                    .thenRun(ssePublisher::close)
                    .whenComplete((unused, throwable) -> {
                        sendError.set(throwable);
                        ssePublisher.close();
                    });
            })
            .start();

        try (SseClient.ServerSentEvent ignored = sseClient.newServerSentEvent(request().url(server.uri().resolve("/streamer").toString()).build(), listener)) {
            listener.assertListenerIsClosed();
        }

        assertThat(sendError.get(), nullValue());
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

        MuAssert.assertNotTimedOut("responseInfoLatch", responseInfoLatch);
        assertThat(responseInfo.get().completedSuccessfully(), is(true));
    }


    @Test
    public void sendThrowsAnExceptionIfTheClientDisconnects() throws InterruptedException {
        AtomicReference<Throwable> thrownException = new AtomicReference<>();
        CountDownLatch somethingPublishedLatch = new CountDownLatch(1);
        CountDownLatch exceptionThrownLatch = new CountDownLatch(1);

        CountDownLatch responseInfoLatch = new CountDownLatch(1);
        AtomicReference<ResponseInfo> responseInfo = new AtomicReference<>(null);

        server = ServerUtils.httpsServerForTest()
            .addHandler(Method.GET, "/streamer", (request, response, pathParams) -> {

                AsyncSsePublisher ssePublisher = AsyncSsePublisher.start(request, response);
                ssePublisher.setResponseCompleteHandler(newValue -> {
                    responseInfo.set(newValue);
                    responseInfoLatch.countDown();
                });
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


        MuAssert.assertNotTimedOut("responseInfoLatch", responseInfoLatch);
        assertThat(responseInfo.get().completedSuccessfully(), is(false));
    }


    @AfterEach
    public void destroy() {
        scaffolding.MuAssert.stopAndCheck(server);
        listener.cleanup();
    }


}
