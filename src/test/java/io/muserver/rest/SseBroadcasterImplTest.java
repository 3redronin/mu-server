package io.muserver.rest;

import io.muserver.MuServer;
import okhttp3.Dispatcher;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import scaffolding.*;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.sse.Sse;
import javax.ws.rs.sse.SseBroadcaster;
import javax.ws.rs.sse.SseEventSink;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static scaffolding.ClientUtils.request;

public class SseBroadcasterImplTest {

    public MuServer server;
    private SseClient.OkSse sseClient;

    @Before
    public void setup() {
        Dispatcher dispatcher = new Dispatcher();
        dispatcher.setMaxRequests(1000);
        dispatcher.setMaxRequestsPerHost(1000);
        sseClient = new SseClient.OkSse(
            ClientUtils.client.newBuilder()
                .dispatcher(dispatcher)
                .build()
        );
    }

    @Test
    public void canPublishMessagesToMultipleClients() throws InterruptedException {

        int messagesPublished = 100;
        int numberOfSubscribers = 100;
        CountDownLatch subscriptionLatch = new CountDownLatch(numberOfSubscribers);

        @Path("/streamer")
        class Streamer {

            private final Sse sse = MuRuntimeDelegate.createSseFactory();
            private final SseBroadcaster broadcaster = sse.newBroadcaster();

            @GET
            @Path("register")
            @Produces(MediaType.SERVER_SENT_EVENTS)
            public void eventStream(@Context SseEventSink eventSink) {
                broadcaster.register(eventSink);
                subscriptionLatch.countDown();
            }

            public void sendMessages() {
                for (int i = 0; i < messagesPublished; i++) {
                    broadcaster.broadcast(sse.newEvent("This is message " + i));
                }
            }

            public void endBroadcast() {
                broadcaster.close();
            }
        }

        Streamer streamer = new Streamer();
        server = ServerUtils.httpsServerForTest().addHandler(restHandler(streamer)).start();

        List<TestSseClient> listeners = new ArrayList<>();
        for (int i = 0; i < numberOfSubscribers; i++) {
            TestSseClient listener = new TestSseClient();
            sseClient.newServerSentEvent(request().url(server.uri().resolve("/streamer/register").toString()).build(), listener);
            listeners.add(listener);
        }

        MuAssert.assertNotTimedOut("Timed out waiting for subscriptions", subscriptionLatch);

        streamer.sendMessages();
        streamer.endBroadcast();

        List<String> expected = new ArrayList<>();
        expected.add("open");
        for (int i = 0; i < messagesPublished; i++) {
            expected.add("message=This is message " + i + "        event=message        id=null");
        }
        expected.add("retryError");
        expected.add("closed");

        for (TestSseClient listener : listeners) {
            listener.assertListenerIsClosed();
            assertThat(listener.receivedMessages, equalTo(expected));
        }
    }


    @After
    public void stop() {
        MuAssert.stopAndCheck(server);
    }

}