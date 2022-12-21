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
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.ext.MessageBodyWriter;
import javax.ws.rs.sse.Sse;
import javax.ws.rs.sse.SseBroadcaster;
import javax.ws.rs.sse.SseEventSink;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
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

    @Test
    public void badSinksAreRemoved() throws Exception {

        int numberOfSubscribers = 10;
        CountDownLatch subscriptionLatch = new CountDownLatch(numberOfSubscribers);
        List<String> errors = new CopyOnWriteArrayList<>();

        class Message {
            public final int data;

            Message(int data) {
                this.data = data;
            }
        }

        @Path("/streamer")
        class Streamer {

            private final Sse sse = MuRuntimeDelegate.createSseFactory();
            private final SseBroadcaster broadcaster = sse.newBroadcaster();

            public Streamer() {
                broadcaster.onError((sseEventSink, throwable) -> {
                    errors.add(throwable.getMessage());
                });
            }

            @GET
            @Path("register")
            @Produces(MediaType.SERVER_SENT_EVENTS)
            public void eventStream(@Context SseEventSink eventSink) {
                broadcaster.register(eventSink);
                subscriptionLatch.countDown();
            }

            public void sendMessages(Message message) {
                broadcaster.broadcast(sse.newEventBuilder().data(message).build());
            }

            public void endBroadcast() {
                broadcaster.close();
            }
        }

        Streamer streamer = new Streamer();
        server = ServerUtils.httpsServerForTest().addHandler(
            restHandler(streamer)
                .addCustomWriter(new MessageBodyWriter<Message>() {
                    boolean oneSent = false;

                    @Override
                    public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
                        return type.equals(Message.class);
                    }

                    @Override
                    public synchronized void writeTo(Message message, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType, MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException, WebApplicationException {
                        if (oneSent) {
                            try (OutputStreamWriter os = new OutputStreamWriter(entityStream)) {
                                os.append(String.valueOf(message.data));
                            }
                        } else {
                            oneSent = true;
                            throw new IOException("Simulating IO exception");
                        }
                    }
                })
        ).start();

        List<TestSseClient> listeners = new ArrayList<>();
        for (int i = 0; i < numberOfSubscribers; i++) {
            TestSseClient listener = new TestSseClient();
            sseClient.newServerSentEvent(request().url(server.uri().resolve("/streamer/register").toString()).build(), listener);
            listeners.add(listener);
        }

        MuAssert.assertNotTimedOut("Timed out waiting for subscriptions", subscriptionLatch);

        streamer.sendMessages(new Message(1));
        streamer.sendMessages(new Message(2));
        streamer.endBroadcast();

        assertThat(errors.toString(), errors, contains("Simulating IO exception"));

        int numWithErrors = 0;
        for (TestSseClient listener : listeners) {
            listener.assertListenerIsClosed();

            if (listener.receivedMessages.size() == 3) {
                assertThat(listener.receivedMessages, contains("open", "retryError", "closed"));
                numWithErrors++;
            } else {
                assertThat(listener.receivedMessages,
                    contains("open", "message=1        event=message        id=null", "message=2        event=message        id=null", "retryError", "closed"));
            }
        }
        assertThat(numWithErrors, is(1));
    }

    @Test
    public void disconnectedClientsAreRemovedFromBroadcasting() throws InterruptedException {
        List<String> errors = new CopyOnWriteArrayList<>();
        CountDownLatch subscriptionLatch = new CountDownLatch(1);
        CountDownLatch exceptionThrownLatch = new CountDownLatch(1);

        Sse sse = MuRuntimeDelegate.createSseFactory();
        SseBroadcaster broadcaster = sse.newBroadcaster();
        broadcaster.onError((sseEventSink, throwable) -> {
            errors.add(throwable.getMessage());
            exceptionThrownLatch.countDown();
        });

        @Path("/streamer")
        class Streamer {
            @GET
            @Path("register")
            @Produces(MediaType.SERVER_SENT_EVENTS)
            public void eventStream(@Context SseEventSink eventSink) {
                broadcaster.register(eventSink);
                subscriptionLatch.countDown();
            }
        }

        server = ServerUtils.httpsServerForTest()
            .addHandler(RestHandlerBuilder.restHandler(new Streamer()))
            .start();

        try (SseClient.ServerSentEvent ignored = sseClient.newServerSentEvent(request().url(server.uri().resolve("/streamer/register").toString()).build(), new TestSseClient())) {
            assertThat("Timed out waiting for SSE publisher to start", subscriptionLatch.await(10, TimeUnit.SECONDS), is(true));
            assertThat(MuRuntimeDelegate.connectedSinksCount(broadcaster), is(1));
        }
        MuAssert.assertNotTimedOut("exceptionThrownLatch", exceptionThrownLatch);

        assertThat(MuRuntimeDelegate.connectedSinksCount(broadcaster), is(0));

    }

    @After
    public void stop() {
        MuAssert.stopAndCheck(server);
    }

}