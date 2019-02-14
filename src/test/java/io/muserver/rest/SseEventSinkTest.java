package io.muserver.rest;


import io.muserver.MuServer;
import org.junit.After;
import org.junit.Test;
import scaffolding.ClientUtils;
import scaffolding.MuAssert;
import scaffolding.SseClient;
import scaffolding.TestSseClient;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
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

import static io.muserver.MuServerBuilder.httpServer;
import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static scaffolding.ClientUtils.request;

public class SseEventSinkTest {
    public MuServer server;
    private final SseClient.OkSse sseClient = new SseClient.OkSse(ClientUtils.client);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final TestSseClient listener = new TestSseClient();

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

        server = httpServer().addHandler(
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
    public void theCallbacksCanBeUsedToDetectClientDisconnections() throws Exception {
        CountDownLatch failureLatch = new CountDownLatch(1);
        @Path("/streamer")
        class Streamer {

            public void sendStuff(SseEventSink sink, Sse sse) {
                sink.send(sse.newEvent("Hello"))
                    .whenComplete((o, throwable) -> {
                        if (throwable == null) {
                            System.out.println("Will send again");
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

        server = httpServer().addHandler(restHandler(new Streamer())).start();

        try (SseClient.ServerSentEvent ignored = sseClient.newServerSentEvent(request().url(server.uri().resolve("/streamer/eventStream").toString()).build(), listener)) {
            Thread.sleep(50);
        }
        MuAssert.assertNotTimedOut("Timed out waiting for error", failureLatch);
    }


    @After
    public void stop() {
        MuAssert.stopAndCheck(server);
        listener.cleanup();
    }
}
