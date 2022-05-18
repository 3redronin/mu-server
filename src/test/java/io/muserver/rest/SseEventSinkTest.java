package io.muserver.rest;


import io.muserver.MuServer;
import org.junit.After;
import org.junit.Test;
import scaffolding.*;

import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.ext.MessageBodyWriter;
import javax.ws.rs.sse.Sse;
import javax.ws.rs.sse.SseEventSink;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static scaffolding.ClientUtils.request;
import static scaffolding.MuAssert.assertNotTimedOut;

public class SseEventSinkTest {
    public MuServer server;
    private final SseClient.OkSse sseClient = new SseClient.OkSse(ClientUtils.client);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private TestSseClient listener = new TestSseClient();

    @Test
    public void canPublishMessagesAndCustomDataWritersAreUsed() throws InterruptedException {

        class Dog {
            final boolean hasTail;
            final String name;

            Dog(boolean hasTail, String name) {
                this.hasTail = hasTail;
                this.name = name;
            }
        }
        class DogWriter implements MessageBodyWriter<Dog> {

            @Override
            public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
                return type.equals(Dog.class);
            }

            @Override
            public void writeTo(Dog dog, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType, MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException, WebApplicationException {
                entityStream.write(("Dog " + dog.name + " has tail? " + dog.hasTail).getBytes(UTF_8));
            }
        }

        @Path("/streamer")
        class Streamer {

            @GET
            @Path("eventStream")
            @Produces(MediaType.SERVER_SENT_EVENTS)
            public void eventStream(@Context SseEventSink eventSink,
                                    @Context Sse sse) {
                executor.execute(() -> {
                    try (SseEventSink sink = eventSink) {
                        sink.send(sse.newEventBuilder()
                            .reconnectDelay(100000)
                            .comment("a comment")
                            .data("event1")
                            .build());
                        sink.send(sse.newEvent("event2"));
                        sink.send(sse.newEventBuilder().data(new Dog(true, "Little")).build());
                        sink.send(sse.newEventBuilder().data(123).name("Number").id("123").build());
                    }
                });
            }
        }

        server = ServerUtils.httpsServerForTest().addHandler(
            restHandler(new Streamer())
                .addCustomWriter(new DogWriter())
        ).start();

        try (SseClient.ServerSentEvent ignored = sseClient.newServerSentEvent(request().url(server.uri().resolve("/streamer/eventStream").toString()).build(), listener)) {
            listener.assertListenerIsClosed();
        }
        assertThat(listener.receivedMessages.subList(0, 7), equalTo(asList(
            "open",
            "retryTime=100000",
            "comment=a comment",
            "message=event1        event=message        id=null",
            "message=event2        event=message        id=null",
            "message=Dog Little has tail? true        event=message        id=null",
            "message=123        event=Number        id=123")));
    }

    @Test
    public void errorsResultInClientDisconnection() throws InterruptedException {

        class Dog {
        }
        @Produces("application/json")
        class DogWriter implements MessageBodyWriter<Dog> {
            @Override
            public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
                return type.equals(Dog.class);
            }

            @Override
            public void writeTo(Dog dog, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType, MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException, WebApplicationException {
                entityStream.write("{}".getBytes(UTF_8));
            }
        }

        AtomicReference<Throwable> error = new AtomicReference<>();

        @Path("/streamer")
        class Streamer {

            @GET
            @Path("eventStreamWithError")
            @Produces(MediaType.SERVER_SENT_EVENTS)
            public void eventStreamWithError(@Context SseEventSink eventSink, @Context Sse sse) {
                eventSink.send(sse.newEventBuilder().data(new Dog()).build())
                    .whenComplete((o, throwable) -> {
                        error.set(throwable);
                        eventSink.close();
                    });
            }

            @GET
            @Path("eventStreamWithoutError")
            @Produces(MediaType.SERVER_SENT_EVENTS)
            public void eventStreamWithoutError(@Context SseEventSink eventSink, @Context Sse sse) {
                eventSink.send(sse.newEventBuilder().data(new Dog()).mediaType(MediaType.APPLICATION_JSON_TYPE).build())
                    .thenRun(eventSink::close);
            }
        }

        server = ServerUtils.httpsServerForTest().addHandler(
            restHandler(new Streamer())
                .addCustomWriter(new DogWriter())
        ).start();

        try (SseClient.ServerSentEvent ignored = sseClient.newServerSentEvent(request(server.uri().resolve("/streamer/eventStreamWithoutError")).build(), listener)) {
            listener.assertListenerIsClosed();
        }
        assertThat(listener.receivedMessages, equalTo(asList("open", "message={}        event=message        id=null", "retryError", "closed")));

        listener = new TestSseClient();
        try (SseClient.ServerSentEvent ignored = sseClient.newServerSentEvent(request(server.uri().resolve("/streamer/eventStreamWithError")).build(), listener)) {
            listener.assertListenerIsClosed();
        }
        assertThat(listener.receivedMessages, equalTo(asList("open", "retryError", "closed")));
        assertThat(error.get(), instanceOf(InternalServerErrorException.class));
    }

    @Test
    public void theCallbacksCanBeUsedToDetectClientDisconnections() {
        CountDownLatch oneSentLatch = new CountDownLatch(1);
        CountDownLatch responseClosedLatch = new CountDownLatch(1);
        CountDownLatch failureLatch = new CountDownLatch(1);
        @Path("/streamer")
        class Streamer {

            private void sendStuff(SseEventSink sink, Sse sse) {
                sink.send(sse.newEvent("Hello"))
                    .whenCompleteAsync((o, throwable) -> {
                        if (throwable == null) {
                            oneSentLatch.countDown();
                            MuAssert.assertNotTimedOut("Waiting for response to close", responseClosedLatch);
                            sendStuff(sink, sse);
                        } else {
                            failureLatch.countDown();
                        }
                    });
            }

            @GET
            @Path("eventStream")
            @Produces(MediaType.SERVER_SENT_EVENTS)
            public void eventStream(@Context SseEventSink eventSink,
                                    @Context Sse sse) {
                sendStuff(eventSink, sse);
            }
        }

        server = ServerUtils.httpsServerForTest().addHandler(restHandler(new Streamer()))
            .addResponseCompleteListener(info -> {
                responseClosedLatch.countDown();
            })
            .start();
        try (SseClient.ServerSentEvent ignored = sseClient.newServerSentEvent(request(server.uri().resolve("/streamer/eventStream")).build(), listener)) {
            assertNotTimedOut("Waiting for one message", oneSentLatch);
        }
        assertNotTimedOut("Timed out waiting for error", failureLatch);
    }

    @Test
    public void whenTheClientDisconnectsTheEventSinkStatusIsChanged() {
        CountDownLatch oneSentLatch = new CountDownLatch(1);
        CountDownLatch closedLatch = new CountDownLatch(1);
        @Path("/streamer")
        class Streamer {

            @GET
            @Path("eventStream")
            @Produces(MediaType.SERVER_SENT_EVENTS)
            public void eventStream(@Context SseEventSink eventSink,
                                    @Context Sse sse) {
                eventSink.send(sse.newEvent("Blah")).whenCompleteAsync((o, throwable) -> {
                    if (throwable == null) {
                        oneSentLatch.countDown();
                        for (int i = 0; i < 1000; i++) {
                            if (eventSink.isClosed()) {
                                closedLatch.countDown();
                                break;
                            }
                            try {
                                Thread.sleep(10);
                            } catch (InterruptedException e) {
                                throw new RuntimeException("Error while sleeping", e);
                            }
                        }
                    } else {
                        throwable.printStackTrace();
                    }
                });

            }
        }

        server = ServerUtils.httpsServerForTest().addHandler(restHandler(new Streamer()))
            .start();
        try (SseClient.ServerSentEvent ignored = sseClient.newServerSentEvent(request(server.uri().resolve("/streamer/eventStream")).build(), listener)) {
            assertNotTimedOut("Waiting for one message", oneSentLatch);
        }
        assertNotTimedOut("Timed out waiting for closedLatch", closedLatch);
    }


    @After
    public void stop() {
        MuAssert.stopAndCheck(server);
        listener.cleanup();
    }
}
