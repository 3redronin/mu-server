package io.muserver;

import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import scaffolding.ClientUtils;
import scaffolding.SseClient;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.muserver.MuServerBuilder.httpsServer;
import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.request;

public class SsePublisherTest {

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

                SsePublisher ssePublisher = SsePublisher.start(request, response);
                executor.submit(() -> {
                    try {
                        ssePublisher.sendComment("this is a comment");
                        ssePublisher.send("Just a message");
                        ssePublisher.send("A message and event", "customevent");
                        ssePublisher.setClientReconnectTime(3, TimeUnit.SECONDS);
                        ssePublisher.send("A message and ID", null, "myid");
                        ssePublisher.send("A message and event and ID", "customevent", "myid");
                        ssePublisher.sendComment("this is a comment 2");
                        ssePublisher.send(multilineJson, null, null);
                    } catch (IOException e) {
                        // the user has disconnected
                    } finally {
                        ssePublisher.close();
                    }
                });

            })
            .start();

        SseClient.ServerSentEvent clientHandle = sseClient.newServerSentEvent(request().url(server.uri().resolve("/streamer").toString()).build(), listener);

        assertThat("Timed out waiting for SSE stream to close", listener.completedLatch.await(1, TimeUnit.MINUTES), is(true));
        clientHandle.close();
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
        server = httpsServer()
            .addHandler(Method.GET, "/streamer", (request, response, pathParams) -> {

                SsePublisher ssePublisher = SsePublisher.start(request, response);
                executor.submit(() -> {
                    int i = 0;
                    while (true) {
                        try {
                            ssePublisher.send("This is message " + i);
                            somethingPublishedLatch.countDown();
                            Thread.sleep(200);
                        } catch (Throwable e) {
                            thrownException.set(e);
                            exceptionThrownLatch.countDown();
                            break;
                        }
                        i++;
                    }
                });

            })
            .start();

        SseClient.ServerSentEvent sse = sseClient.newServerSentEvent(request().url(server.uri().resolve("/streamer").toString()).build(), listener);
        assertThat("Timed out waiting for SSE publisher to start", somethingPublishedLatch.await(10, TimeUnit.SECONDS), is(true));
        sse.close();

        assertThat("Timed out waiting for SSE publisher to stop", exceptionThrownLatch.await(10, TimeUnit.SECONDS), is(true));

        assertThat(thrownException.get(), is(notNullValue()));
        assertThat(thrownException.get(), is(instanceOf(IOException.class)));

    }


    @After
    public void destroy() {
        scaffolding.MuAssert.stopAndCheck(server);
        if (listener != null) listener.cleanup();
    }

    private static class TestSseClient implements SseClient.ServerSentEvent.Listener {
        final List<String> receivedMessages = new ArrayList<>();
        final CountDownLatch completedLatch = new CountDownLatch(1);
        private Response response;

        @Override
        public void onOpen(SseClient.ServerSentEvent sse, Response response) {
            receivedMessages.add("open");
            this.response = response;
        }

        @Override
        public void onMessage(SseClient.ServerSentEvent sse, String id, String event, String message) {
            receivedMessages.add("message=" + message + "        event=" + event + "        id=" + id);
        }

        @Override
        public void onComment(SseClient.ServerSentEvent sse, String comment) {
            receivedMessages.add("comment=" + comment);
        }

        @Override
        public boolean onRetryTime(SseClient.ServerSentEvent sse, long milliseconds) {
            receivedMessages.add("retryTime=" + milliseconds);
            return true;
        }

        @Override
        public boolean onRetryError(SseClient.ServerSentEvent sse, Throwable throwable, Response response) {
            receivedMessages.add("retryError");
            return false;
        }

        @Override
        public void onClosed(SseClient.ServerSentEvent sse) {
            receivedMessages.add("closed");
            completedLatch.countDown();
        }

        @Override
        public Request onPreRetry(SseClient.ServerSentEvent sse, Request originalRequest) {
            receivedMessages.add("onPreRetry");
            return originalRequest;
        }

        public void cleanup() {
            if (response != null && response.body() != null) {
                response.body().close();
            }
        }
    }
}
