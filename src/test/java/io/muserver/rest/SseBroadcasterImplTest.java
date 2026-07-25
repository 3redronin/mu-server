package io.muserver.rest;

import io.muserver.AsyncSsePublisher;
import io.muserver.MuRequest;
import io.muserver.MuResponse;
import io.muserver.MuServer;
import io.muserver.ResponseCompleteListener;
import io.muserver.ResponseInfo;
import io.muserver.ResponseState;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseBroadcaster;
import jakarta.ws.rs.sse.SseEventSink;
import okhttp3.Dispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import scaffolding.*;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.lang.annotation.Annotation;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static scaffolding.ClientUtils.request;
import static scaffolding.MuAssert.assertEventually;

public class SseBroadcasterImplTest {

    public MuServer server;
    private SseClient.OkSse sseClient;

    @BeforeEach
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
    public void whenSinksAreClosedFromTheServerTheOnCloseMethodIsCalled() throws InterruptedException {

        @Path("/streamer")
        class Streamer {

            private final Sse sse = MuRuntimeDelegate.createSseFactory();
            private final SseBroadcaster broadcaster = sse.newBroadcaster();
            public final List<SseEventSink> closedSinks = new ArrayList<>();

            public Streamer() {
                broadcaster.onClose(closedSinks::add);
            }

            @GET
            @Path("register")
            @Produces(MediaType.SERVER_SENT_EVENTS)
            public void eventStream(@Context SseEventSink eventSink) {
                broadcaster.register(eventSink);
                eventSink.close();
                broadcaster.broadcast(sse.newEvent("Hello"));
            }

            public void endBroadcast() {
                broadcaster.close();
            }
        }

        Streamer streamer = new Streamer();
        server = ServerUtils.httpsServerForTest().addHandler(restHandler(streamer)).start();

        TestSseClient listener = new TestSseClient();
        SseClient.ServerSentEvent sse = sseClient.newServerSentEvent(request().url(server.uri().resolve("/streamer/register").toString()).build(), listener);
        assertEventually(() -> streamer.closedSinks, hasSize(1));
        sse.close();
        streamer.endBroadcast();
        listener.assertListenerIsClosed();
    }

    @Test
    public void synchronousSinkFailuresDoNotStopBroadcasting() throws Exception {
        RuntimeException sendFailure = new RuntimeException("Sink failed before send");
        AtomicReference<Throwable> reportedFailure = new AtomicReference<>();
        AtomicReference<OutboundSseEvent> receivedEvent = new AtomicReference<>();

        class FailingSink implements SseEventSink {
            private boolean closed;

            @Override
            public CompletionStage<?> send(OutboundSseEvent event) {
                throw sendFailure;
            }

            @Override
            public boolean isClosed() {
                return closed;
            }

            @Override
            public void close() {
                closed = true;
            }
        }

        class RecordingSink implements SseEventSink {
            @Override
            public CompletionStage<?> send(OutboundSseEvent event) {
                receivedEvent.set(event);
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public boolean isClosed() {
                return false;
            }

            @Override
            public void close() {
            }
        }

        SseBroadcasterImpl broadcaster = new SseBroadcasterImpl();
        FailingSink failingSink = new FailingSink();
        broadcaster.register(failingSink);
        broadcaster.register(new RecordingSink());
        broadcaster.onError((sink, throwable) -> reportedFailure.set(throwable));

        OutboundSseEvent event = MuRuntimeDelegate.createSseFactory().newEvent("hello");
        broadcaster.broadcast(event).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertThat(reportedFailure.get(), sameInstance(sendFailure));
        assertThat(failingSink.isClosed(), is(true));
        assertThat(receivedEvent.get(), sameInstance(event));
        assertThat(broadcaster.connectedSinksCount(), is(1));
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
                    String error = throwable.getMessage();
                    errors.add(error == null ? throwable.toString() : error);
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


        var commentException = new AtomicReference<Throwable>();
        var awakener = new Thread(() -> {
            try {
                while (true) {
                    broadcaster.broadcast(sse.newEventBuilder().comment("hi").build()).toCompletableFuture().get();
                    Thread.sleep(1000);
                }
            } catch (InterruptedException e) {
                // stopping
            } catch (Throwable e) {
                commentException.set(e);
            }
        });
        awakener.start();

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

        awakener.interrupt();
        awakener.join();
        assertThat(commentException.get(), nullValue());

    }

    @Test
    public void synchronousSendFailureRemovesSinkAndCompletesBroadcast() throws Exception {
        IllegalStateException sendFailure = new IllegalStateException("closed during broadcast");
        List<Throwable> errors = new ArrayList<>();
        List<SseEventSink> closedSinks = new ArrayList<>();
        SseBroadcasterImpl broadcaster = new SseBroadcasterImpl();
        broadcaster.onError((sink, throwable) -> errors.add(throwable));
        broadcaster.onClose(closedSinks::add);
        CountDownLatch sendsStarted = new CountDownLatch(2);
        CountDownLatch releaseSends = new CountDownLatch(1);

        SseEventSink sink = new SseEventSink() {
            @Override
            public boolean isClosed() {
                return false;
            }

            @Override
            public CompletionStage<?> send(jakarta.ws.rs.sse.OutboundSseEvent event) {
                sendsStarted.countDown();
                try {
                    releaseSends.await(1, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                throw sendFailure;
            }

            @Override
            public void close() {
            }
        };
        broadcaster.register(sink);

        OutboundSseEvent event = MuRuntimeDelegate.createSseFactory().newEvent("Hello");
        CompletableFuture<?> first = CompletableFuture.runAsync(() -> broadcaster.broadcast(event).toCompletableFuture().join());
        CompletableFuture<?> second = CompletableFuture.runAsync(() -> broadcaster.broadcast(event).toCompletableFuture().join());
        assertThat(sendsStarted.await(1, TimeUnit.SECONDS), is(true));
        releaseSends.countDown();
        CompletableFuture.allOf(first, second).get(1, TimeUnit.SECONDS);

        assertThat(errors, empty());
        assertThat(closedSinks, contains(sink));
        assertThat(broadcaster.connectedSinksCount(), is(0));
    }

    @Test
    public void closeMarksTheBroadcasterClosedBeforeClosingSinks() {
        SseBroadcasterImpl broadcaster = new SseBroadcasterImpl();
        AtomicBoolean sinkClosed = new AtomicBoolean();
        AtomicBoolean reentrantRegistrationRejected = new AtomicBoolean();
        SseEventSink sink = sink(sinkClosed);

        broadcaster.onClose(closedSink -> {
            try {
                broadcaster.register(sink(new AtomicBoolean()));
            } catch (IllegalStateException expected) {
                reentrantRegistrationRejected.set(true);
            }
        });
        broadcaster.register(sink);

        broadcaster.close();

        assertThat(sinkClosed.get(), is(true));
        assertThat(reentrantRegistrationRejected.get(), is(true));
        assertThat(broadcaster.connectedSinksCount(), is(0));
    }

    @Test
    public void closeDoesNotInvokeApplicationCallbacksWhileHoldingBroadcasterLock() {
        SseBroadcasterImpl broadcaster = new SseBroadcasterImpl();
        AtomicInteger crossThreadProbes = new AtomicInteger();
        Runnable probeBroadcasterFromAnotherThread = () -> {
            CompletableFuture<?> probe = CompletableFuture.runAsync(() ->
                assertThrows(IllegalStateException.class,
                    () -> broadcaster.register(sink(new AtomicBoolean()))));
            try {
                probe.get(1, TimeUnit.SECONDS);
                crossThreadProbes.incrementAndGet();
            } catch (Exception e) {
                throw new AssertionError("Broadcaster lock was held while invoking a callback", e);
            }
        };
        SseEventSink sink = new SseEventSink() {
            @Override
            public boolean isClosed() {
                return false;
            }

            @Override
            public CompletionStage<?> send(OutboundSseEvent event) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public void close() {
                probeBroadcasterFromAnotherThread.run();
            }
        };
        broadcaster.onClose(closedSink -> probeBroadcasterFromAnotherThread.run());
        broadcaster.register(sink);

        broadcaster.close();

        assertThat(crossThreadProbes.get(), is(2));
    }

    @Test
    public void nonCascadingCloseLeavesSinksOpen() {
        SseBroadcasterImpl broadcaster = new SseBroadcasterImpl();
        AtomicBoolean sinkClosed = new AtomicBoolean();
        AtomicBoolean closeListenerCalled = new AtomicBoolean();
        broadcaster.onClose(closedSink -> closeListenerCalled.set(true));
        broadcaster.register(sink(sinkClosed));

        broadcaster.close(false);

        assertThat(sinkClosed.get(), is(false));
        assertThat(closeListenerCalled.get(), is(false));
        assertThat(broadcaster.connectedSinksCount(), is(0));
    }

    @Test
    public void registerAndCloseAreSerialized() throws Exception {
        SseBroadcasterImpl broadcaster = new SseBroadcasterImpl();
        CountDownLatch registrationStarted = new CountDownLatch(1);
        CountDownLatch allowRegistrationToComplete = new CountDownLatch(1);
        class BlockingSink extends JaxSseEventSinkImpl {
            private BlockingSink() {
                super(null, null, null);
            }

            @Override
            Runnable addResponseCompleteHandler(ResponseCompleteListener listener) {
                registrationStarted.countDown();
                try {
                    assertThat(allowRegistrationToComplete.await(1, TimeUnit.SECONDS), is(true));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return () -> { };
            }
        }

        CompletableFuture<?> registration = CompletableFuture.runAsync(() ->
            broadcaster.register(new BlockingSink()));
        assertThat(registrationStarted.await(1, TimeUnit.SECONDS), is(true));

        CountDownLatch closeStarted = new CountDownLatch(1);
        CompletableFuture<?> close = CompletableFuture.runAsync(() -> {
            closeStarted.countDown();
            broadcaster.close(false);
        });
        assertThat(closeStarted.await(1, TimeUnit.SECONDS), is(true));

        try {
            assertThrows(TimeoutException.class, () -> close.get(100, TimeUnit.MILLISECONDS));
        } finally {
            allowRegistrationToComplete.countDown();
        }
        registration.get(1, TimeUnit.SECONDS);
        close.get(1, TimeUnit.SECONDS);

        assertThat(broadcaster.connectedSinksCount(), is(0));
        assertThrows(IllegalStateException.class,
            () -> broadcaster.register(sink(new AtomicBoolean())));
    }

    @Test
    public void everyOperationOtherThanCloseIsRejectedAfterClose() {
        SseBroadcasterImpl broadcaster = new SseBroadcasterImpl();
        broadcaster.close(false);

        assertThrows(IllegalStateException.class,
            () -> broadcaster.register(sink(new AtomicBoolean())));
        assertThrows(IllegalStateException.class,
            () -> broadcaster.broadcast(new JaxOutboundSseEventBuilder().data("event").build()));
        assertThrows(IllegalStateException.class,
            () -> broadcaster.onClose(closedSink -> { }));
        assertThrows(IllegalStateException.class,
            () -> broadcaster.onError((closedSink, error) -> { }));
    }

    @Test
    public void broadcastingWithNoSinksCompletesImmediately() throws Exception {
        SseBroadcasterImpl broadcaster = new SseBroadcasterImpl();

        broadcaster.broadcast(new JaxOutboundSseEventBuilder().data("event").build())
            .toCompletableFuture().get(1, TimeUnit.SECONDS);
    }

    @Test
    public void concurrentCloseAndBroadcastNotifySinkCloseOnlyOnce() throws Exception {
        SseBroadcasterImpl broadcaster = new SseBroadcasterImpl();
        AtomicBoolean closed = new AtomicBoolean();
        AtomicInteger notifications = new AtomicInteger();
        CountDownLatch checkingClosed = new CountDownLatch(1);
        CountDownLatch releaseClosedCheck = new CountDownLatch(1);
        SseEventSink sink = new SseEventSink() {
            @Override
            public boolean isClosed() {
                checkingClosed.countDown();
                try {
                    assertThat(releaseClosedCheck.await(1, TimeUnit.SECONDS), is(true));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return closed.get();
            }

            @Override
            public CompletionStage<?> send(OutboundSseEvent event) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public void close() {
                closed.set(true);
            }
        };
        broadcaster.onClose(closedSink -> notifications.incrementAndGet());
        broadcaster.register(sink);

        CompletableFuture<?> broadcast = CompletableFuture.runAsync(() ->
            broadcaster.broadcast(new JaxOutboundSseEventBuilder().data("event").build())
                .toCompletableFuture().join());
        assertThat(checkingClosed.await(1, TimeUnit.SECONDS), is(true));
        broadcaster.close();
        releaseClosedCheck.countDown();
        broadcast.get(1, TimeUnit.SECONDS);

        assertThat(notifications.get(), is(1));
    }

    @Test
    public void nonCascadingCloseDoesNotSuppressInFlightClosedSinkNotification() throws Exception {
        SseBroadcasterImpl broadcaster = new SseBroadcasterImpl();
        AtomicBoolean closed = new AtomicBoolean();
        AtomicInteger notifications = new AtomicInteger();
        CountDownLatch checkingClosed = new CountDownLatch(1);
        CountDownLatch releaseClosedCheck = new CountDownLatch(1);
        SseEventSink sink = new SseEventSink() {
            @Override
            public boolean isClosed() {
                checkingClosed.countDown();
                try {
                    assertThat(releaseClosedCheck.await(1, TimeUnit.SECONDS), is(true));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return closed.get();
            }

            @Override
            public CompletionStage<?> send(OutboundSseEvent event) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public void close() {
                closed.set(true);
            }
        };
        broadcaster.onClose(closedSink -> notifications.incrementAndGet());
        broadcaster.register(sink);

        CompletableFuture<?> broadcast = CompletableFuture.runAsync(() ->
            broadcaster.broadcast(new JaxOutboundSseEventBuilder().data("event").build())
                .toCompletableFuture().join());
        assertThat(checkingClosed.await(1, TimeUnit.SECONDS), is(true));
        broadcaster.close(false);
        closed.set(true);
        releaseClosedCheck.countDown();
        broadcast.get(1, TimeUnit.SECONDS);

        assertThat(notifications.get(), is(1));
    }

    @Test
    public void cascadingCloseDoesNotSuppressInFlightSendError() throws Exception {
        SseBroadcasterImpl broadcaster = new SseBroadcasterImpl();
        CompletableFuture<Void> sendResult = new CompletableFuture<>();
        CountDownLatch closeStarted = new CountDownLatch(1);
        CountDownLatch releaseClose = new CountDownLatch(1);
        AtomicInteger closeCalls = new AtomicInteger();
        AtomicInteger errorNotifications = new AtomicInteger();
        SseEventSink sink = new SseEventSink() {
            @Override
            public boolean isClosed() {
                return false;
            }

            @Override
            public CompletionStage<?> send(OutboundSseEvent event) {
                return sendResult;
            }

            @Override
            public void close() {
                if (closeCalls.incrementAndGet() == 1) {
                    closeStarted.countDown();
                    try {
                        assertThat(releaseClose.await(1, TimeUnit.SECONDS), is(true));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                }
            }
        };
        broadcaster.onError((failedSink, error) -> errorNotifications.incrementAndGet());
        broadcaster.register(sink);
        broadcaster.broadcast(new JaxOutboundSseEventBuilder().data("event").build());

        CompletableFuture<?> close = CompletableFuture.runAsync(broadcaster::close);
        assertThat(closeStarted.await(1, TimeUnit.SECONDS), is(true));
        sendResult.completeExceptionally(new IOException("send failed"));

        assertThat(errorNotifications.get(), is(1));
        releaseClose.countDown();
        close.get(1, TimeUnit.SECONDS);
    }

    @Test
    public void nonCascadingCloseSuppressesInFlightSendError() {
        SseBroadcasterImpl broadcaster = new SseBroadcasterImpl();
        CompletableFuture<Void> sendResult = new CompletableFuture<>();
        AtomicInteger closeCalls = new AtomicInteger();
        AtomicInteger errorNotifications = new AtomicInteger();
        SseEventSink sink = new SseEventSink() {
            @Override
            public boolean isClosed() {
                return false;
            }

            @Override
            public CompletionStage<?> send(OutboundSseEvent event) {
                return sendResult;
            }

            @Override
            public void close() {
                closeCalls.incrementAndGet();
            }
        };
        broadcaster.onError((failedSink, error) -> errorNotifications.incrementAndGet());
        broadcaster.register(sink);
        broadcaster.broadcast(new JaxOutboundSseEventBuilder().data("event").build());

        broadcaster.close(false);
        sendResult.completeExceptionally(new IOException("send failed"));

        assertThat(errorNotifications.get(), is(0));
        assertThat(closeCalls.get(), is(0));
    }

    @Test
    public void nonCascadingCloseSuppressesFailureFromAlreadyRemovedRegistration() throws Exception {
        SseBroadcasterImpl broadcaster = new SseBroadcasterImpl();
        CompletableFuture<Void> firstSend = new CompletableFuture<>();
        AtomicInteger closedChecks = new AtomicInteger();
        AtomicInteger closeCalls = new AtomicInteger();
        AtomicInteger errorNotifications = new AtomicInteger();
        SseEventSink sink = new SseEventSink() {
            @Override
            public boolean isClosed() {
                return closedChecks.incrementAndGet() > 1;
            }

            @Override
            public CompletionStage<?> send(OutboundSseEvent event) {
                return firstSend;
            }

            @Override
            public void close() {
                closeCalls.incrementAndGet();
            }
        };
        broadcaster.onError((failedSink, error) -> errorNotifications.incrementAndGet());
        broadcaster.register(sink);

        CompletionStage<?> pendingBroadcast =
            broadcaster.broadcast(new JaxOutboundSseEventBuilder().data("first").build());
        broadcaster.broadcast(new JaxOutboundSseEventBuilder().data("second").build())
            .toCompletableFuture().get(1, TimeUnit.SECONDS);
        broadcaster.close(false);
        firstSend.completeExceptionally(new IOException("send failed"));
        pendingBroadcast.toCompletableFuture().get(1, TimeUnit.SECONDS);

        assertThat(errorNotifications.get(), is(0));
        assertThat(closeCalls.get(), is(0));
    }

    @Test
    public void throwingErrorListenerDoesNotStrandBroadcastOrSkipLaterListeners() throws Exception {
        SseBroadcasterImpl broadcaster = new SseBroadcasterImpl();
        CompletableFuture<Void> sendResult = new CompletableFuture<>();
        AtomicInteger laterListenerCalls = new AtomicInteger();
        broadcaster.onError((failedSink, error) -> {
            throw new IllegalStateException("listener failed");
        });
        broadcaster.onError((failedSink, error) -> laterListenerCalls.incrementAndGet());
        broadcaster.register(new SseEventSink() {
            @Override
            public boolean isClosed() {
                return false;
            }

            @Override
            public CompletionStage<?> send(OutboundSseEvent event) {
                return sendResult;
            }

            @Override
            public void close() {
            }
        });

        CompletionStage<?> broadcast =
            broadcaster.broadcast(new JaxOutboundSseEventBuilder().data("event").build());
        sendResult.completeExceptionally(new IOException("send failed"));

        broadcast.toCompletableFuture().get(1, TimeUnit.SECONDS);
        assertThat(laterListenerCalls.get(), is(1));
    }

    @Test
    public void throwingCloseListenerDoesNotStrandBroadcastOrSkipLaterListeners() throws Exception {
        SseBroadcasterImpl broadcaster = new SseBroadcasterImpl();
        AtomicBoolean closed = new AtomicBoolean(true);
        AtomicInteger laterListenerCalls = new AtomicInteger();
        broadcaster.onClose(closedSink -> {
            throw new IllegalStateException("listener failed");
        });
        broadcaster.onClose(closedSink -> laterListenerCalls.incrementAndGet());
        broadcaster.register(sink(closed));

        broadcaster.broadcast(new JaxOutboundSseEventBuilder().data("event").build())
            .toCompletableFuture().get(1, TimeUnit.SECONDS);

        assertThat(laterListenerCalls.get(), is(1));
    }

    @Test
    public void cascadingCloseReportsSinkCloseFailure() {
        SseBroadcasterImpl broadcaster = new SseBroadcasterImpl();
        IllegalStateException closeFailure = new IllegalStateException("close failed");
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        broadcaster.onError((failedSink, error) -> errors.add(error));
        broadcaster.register(new SseEventSink() {
            @Override
            public boolean isClosed() {
                return false;
            }

            @Override
            public CompletionStage<?> send(OutboundSseEvent event) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public void close() {
                throw closeFailure;
            }
        });

        broadcaster.close();

        assertThat(errors, contains(closeFailure));
    }

    @Test
    public void responseCompleteHandlersCanBeRemovedFromJaxSink() {
        AtomicReference<ResponseCompleteListener> upstreamHandler = new AtomicReference<>();
        AsyncSsePublisher publisher = (AsyncSsePublisher) Proxy.newProxyInstance(
            AsyncSsePublisher.class.getClassLoader(),
            new Class<?>[]{AsyncSsePublisher.class},
            (proxy, method, args) -> {
                if (method.getName().equals("setResponseCompleteHandler")) {
                    upstreamHandler.set((ResponseCompleteListener) args[0]);
                    return null;
                }
                throw new UnsupportedOperationException(method.getName());
            });
        JaxSseEventSinkImpl sink = new JaxSseEventSinkImpl(publisher, null, null);
        AtomicInteger handlerCalls = new AtomicInteger();
        Runnable removeHandler = sink.addResponseCompleteHandler(info -> handlerCalls.incrementAndGet());

        upstreamHandler.get().onComplete(null);
        removeHandler.run();
        removeHandler.run();
        upstreamHandler.get().onComplete(null);

        assertThat(handlerCalls.get(), is(1));
    }

    @Test
    public void nonCascadingCloseRemovesResponseHandlerAndIgnoresRacingCompletion() {
        SseBroadcasterImpl broadcaster = new SseBroadcasterImpl();
        AtomicInteger closeCalls = new AtomicInteger();
        AtomicInteger errorNotifications = new AtomicInteger();
        AtomicBoolean responseCompleteHandlerRemoved = new AtomicBoolean();
        class CapturingSink extends JaxSseEventSinkImpl {
            private ResponseCompleteListener responseCompleteListener;

            private CapturingSink() {
                super(null, null, null);
            }

            @Override
            Runnable addResponseCompleteHandler(ResponseCompleteListener listener) {
                responseCompleteListener = listener;
                return () -> responseCompleteHandlerRemoved.set(true);
            }

            @Override
            public boolean isClosed() {
                return false;
            }

            @Override
            public CompletionStage<?> send(OutboundSseEvent event) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public void close() {
                closeCalls.incrementAndGet();
            }
        }
        CapturingSink sink = new CapturingSink();
        broadcaster.onError((failedSink, error) -> errorNotifications.incrementAndGet());
        broadcaster.register(sink);
        broadcaster.close(false);
        assertThat(responseCompleteHandlerRemoved.get(), is(true));

        MuResponse response = (MuResponse) Proxy.newProxyInstance(
            MuResponse.class.getClassLoader(),
            new Class<?>[]{MuResponse.class},
            (proxy, method, args) -> {
                if (method.getName().equals("responseState")) {
                    return ResponseState.CLIENT_DISCONNECTED;
                }
                throw new UnsupportedOperationException(method.getName());
            });
        sink.responseCompleteListener.onComplete(new ResponseInfo() {
            @Override
            public long duration() {
                return 0;
            }

            @Override
            public boolean completedSuccessfully() {
                return false;
            }

            @Override
            public MuRequest request() {
                return null;
            }

            @Override
            public MuResponse response() {
                return response;
            }
        });

        assertThat(errorNotifications.get(), is(0));
        assertThat(closeCalls.get(), is(0));
    }

    private static SseEventSink sink(AtomicBoolean closed) {
        return new SseEventSink() {
            @Override
            public boolean isClosed() {
                return closed.get();
            }

            @Override
            public CompletionStage<?> send(OutboundSseEvent event) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public void close() {
                closed.set(true);
            }
        };
    }

    @AfterEach
    public void stop() {
        MuAssert.stopAndCheck(server);
    }

}
