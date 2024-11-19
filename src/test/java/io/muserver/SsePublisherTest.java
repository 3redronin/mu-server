package io.muserver;

import org.junit.After;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scaffolding.*;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.request;

public class SsePublisherTest {

    private MuServer server;
    private final SseClient.OkSse sseClient = new SseClient.OkSse(ClientUtils.client);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final TestSseClient listener = new TestSseClient();
    private static final Logger log = LoggerFactory.getLogger(SsePublisherTest.class);

    @Test
    public void canCall() throws InterruptedException {
        String multilineJson = "{\n" +
            "    \"value2\": \"Something \\n more\",\n" +
            "    \"value1\": \"Something\"\n" +
            "}";
        String multilineJsonWithNewlines = "{\r\n" +
            "    \"value2\": \"Something \\n more\",\r\n" +
            "    \"value1\": \"Something\"\r\n" +
            "}";

        server = ServerUtils.httpsServerForTest()
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
                        ssePublisher.send(multilineJsonWithNewlines, null, null);
                    } catch (Exception e) {
                        log.info("Error while publishing", e);
                    } finally {
                        ssePublisher.close();
                    }
                });

            })
            .start();

        SseClient.ServerSentEvent clientHandle = sseClient.newServerSentEvent(request(server.uri().resolve("/streamer")).build(), listener);
        listener.assertListenerIsClosed();
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
            "message=" + multilineJsonWithNewlines.replaceAll("\\r\\n", "\n") + "        event=message        id=myid",
            "retryError",
            "closed")));
    }

    @Test
    public void largeMessagesCanBeSent() throws Exception {
        String message1 = "<h1>" + StringUtils.randomStringOfLength(100000) + "</h1>";
        String message2 = StringUtils.randomStringOfLength(100000);
        server = ServerUtils.httpsServerForTest()
            .addHandler(Method.GET, "/streamer", (request, response, pathParams) -> {
                SsePublisher ssePublisher = SsePublisher.start(request, response);
                ssePublisher.send(message1, null, null);
                ssePublisher.send(message2, null, "two");
                ssePublisher.close();
            })
            .start();
        SseClient.ServerSentEvent clientHandle = sseClient.newServerSentEvent(request().url(server.uri().resolve("/streamer").toString()).build(), listener);
        listener.assertListenerIsClosed();
        clientHandle.close();
        assertThat(listener.receivedMessages, hasItem("message=" + message1 + "        event=message        id=null"));
        assertThat(listener.receivedMessages, hasItem("message=" + message2 + "        event=message        id=two"));
    }

    @Test
    public void sendThrowsAnExceptionIfTheClientDisconnects() throws InterruptedException {
        AtomicReference<Throwable> thrownException = new AtomicReference<>();
        CountDownLatch somethingPublishedLatch = new CountDownLatch(1);
        CountDownLatch exceptionThrownLatch = new CountDownLatch(1);
        server = ServerUtils.httpsServerForTest()
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
        listener.cleanup();
    }

}
