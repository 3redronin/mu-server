package io.muserver.rest;

import io.muserver.*;
import jakarta.ws.rs.*;
import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.container.CompletionCallback;
import jakarta.ws.rs.container.ConnectionCallback;
import jakarta.ws.rs.container.Suspended;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyWriter;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import scaffolding.ClientUtils;
import scaffolding.MuAssert;
import scaffolding.ServerUtils;
import scaffolding.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static java.util.stream.Collectors.toSet;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;
import static scaffolding.MuAssert.assertNotTimedOut;

public class AsynchronousProcessingTest {

    private final List<ExecutorService> executors = new ArrayList<>();
    private final ExecutorService executor = track(
        Executors.newSingleThreadExecutor(namedThreads("jaxrs-result-"))
    );
    private MuServer server;

    @Test
    public void canUseTheSuspendedAnnotationToGetAnAsyncResponseObject() throws Exception {
        CountDownLatch resumedLatch = new CountDownLatch(1);
        AsyncResponse[] captured = new AsyncResponse[1];
        @Path("samples")
        class Sample {
            @GET
            public void go(@Suspended AsyncResponse ar) {
                executor.submit(() -> {
                    captured[0] = ar;
                    MuAssert.sleep(100);
                    jakarta.ws.rs.core.Response resp = jakarta.ws.rs.core.Response.status(202).entity("Suspended/cancelled/done: " + ar.isSuspended() + ar.isCancelled() + ar.isDone()).build();
                    ar.resume(resp);
                    resumedLatch.countDown();
                });
            }
        }
        this.server = ServerUtils.httpsServerForTest().addHandler(restHandler(new Sample())).start();
        try (Response resp = call(request().url(server.uri().resolve("/samples").toString()))) {
            assertThat(resp.code(), is(202));
            assertThat(resp.body().string(), equalTo("Suspended/cancelled/done: truefalsefalse"));
        }
        assertThat(captured[0].isSuspended(), is(false));
        assertThat(captured[0].isCancelled(), is(false));

        assertThat("Timed out waiting for resumption", resumedLatch.await(1, TimeUnit.MINUTES));
        assertThat(captured[0].isDone(), is(true));
    }


    @Test
    public void returningACompletionStageIsAlsoPossible() throws Exception {
        @Path("samples")
        class Sample {
            @POST
            public CompletionStage<jakarta.ws.rs.core.Response> go(InputStream requestBody) {
                CompletableFuture<jakarta.ws.rs.core.Response> cs = new CompletableFuture<>();
                executor.submit(() -> {
                    String entity;
                    try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
                        MuAssert.sleep(50);
                        Mutils.copy(requestBody, os, 8192);
                        entity = os.toString("utf-8");
                    } catch (Exception ex) {
                        entity = "Error: " + ex;
                    }
                    cs.complete(jakarta.ws.rs.core.Response.status(200).entity(entity).build());
                });
                return cs;
            }
        }
        this.server = ServerUtils.httpsServerForTest().addHandler(restHandler(new Sample())).start();
        String body = StringUtils.randomStringOfLength(68000);
        try (Response resp = call(request(server.uri().resolve("/samples")).post(
            new RequestBody() {
                @Override
                public MediaType contentType() {
                    return MediaType.get("text/plain;charset=utf-8");
                }

                @Override
                public void writeTo(BufferedSink bufferedSink) throws IOException {
                    bufferedSink.writeUtf8(body);
                    bufferedSink.flush();
                    bufferedSink.close();
                }
            }
        ))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), equalTo(body));
        }
    }

    @Test
    public void completionStageResultsAreProcessedOnTheHandlerExecutor() throws Exception {
        var handlerExecutor = track(Executors.newSingleThreadExecutor(namedThreads("handler-")));
        var asyncExecutor = track(Executors.newSingleThreadExecutor(namedThreads("async-")));
        var completionExecutor = track(Executors.newSingleThreadExecutor(namedThreads("completion-")));
        var stage = new CallbackSignallingFuture<ThreadReportingEntity>();

        @Path("samples")
        class Sample {
            @GET
            public CompletionStage<ThreadReportingEntity> go() {
                completionExecutor.execute(() -> {
                    assertNotTimedOut("Waiting for the REST continuation", stage.callbackRegistered);
                    stage.complete(new ThreadReportingEntity());
                });
                return stage;
            }
        }

        server = ServerUtils.httpsServerForTest("http")
            .withHandlerExecutor(handlerExecutor)
            .withAsyncExecutor(asyncExecutor)
            .addHandler(restHandler(new Sample()).addCustomWriter(threadReportingWriter()))
            .start();

        try (Response response = call(request().url(server.uri().resolve("/samples").toString()))) {
            assertThat(response.code(), is(200));
            assertThat(response.body().string(), startsWith("handler-"));
        }
    }

    @Test
    public void suspendedResponsesAreProcessedOnTheHandlerExecutor() throws Exception {
        var handlerExecutor = track(Executors.newSingleThreadExecutor(namedThreads("handler-")));
        var asyncExecutor = track(Executors.newSingleThreadExecutor(namedThreads("async-")));
        var completionExecutor = track(Executors.newSingleThreadExecutor(namedThreads("completion-")));

        @Path("samples")
        class Sample {
            @GET
            public void go(@Suspended AsyncResponse response) {
                completionExecutor.execute(() -> response.resume(new ThreadReportingEntity()));
            }
        }

        server = ServerUtils.httpsServerForTest("http")
            .withHandlerExecutor(handlerExecutor)
            .withAsyncExecutor(asyncExecutor)
            .addHandler(restHandler(new Sample()).addCustomWriter(threadReportingWriter()))
            .start();

        try (Response response = call(request().url(server.uri().resolve("/samples").toString()))) {
            assertThat(response.code(), is(200));
            assertThat(response.body().string(), startsWith("handler-"));
        }
    }

    @Test
    public void asyncResponseTimeoutsUseTheServerTimerAndRunOnTheHandlerExecutor() throws Exception {
        var handlerExecutor = track(Executors.newSingleThreadExecutor(namedThreads("handler-")));
        var asyncExecutor = track(Executors.newSingleThreadExecutor(namedThreads("async-")));
        var timerExecutor = track(Executors.newSingleThreadScheduledExecutor(namedThreads("timer-")));
        var timeoutThread = new CompletableFuture<String>();

        @Path("samples")
        class Sample {
            @GET
            public void go(@Suspended AsyncResponse response) {
                response.setTimeoutHandler(timedOut -> {
                    timeoutThread.complete(Thread.currentThread().getName());
                    timedOut.resume(new ThreadReportingEntity());
                });
                response.setTimeout(1, TimeUnit.MILLISECONDS);
            }
        }

        server = ServerUtils.httpsServerForTest("http")
            .withHandlerExecutor(handlerExecutor)
            .withAsyncExecutor(asyncExecutor)
            .withTimerExecutor(timerExecutor)
            .addHandler(restHandler(new Sample()).addCustomWriter(threadReportingWriter()))
            .start();

        try (Response response = call(request().url(server.uri().resolve("/samples").toString()))) {
            assertThat(response.code(), is(200));
            assertThat(response.body().string(), startsWith("handler-"));
        }
        assertThat(timeoutThread.get(5, TimeUnit.SECONDS), startsWith("handler-"));
    }

    @Test
    public void exceptionalCompletionStagesResumeRequestProcessing() throws Exception {
        var stage = new CallbackSignallingFuture<String>();

        @Path("samples")
        class Sample {
            @GET
            public CompletionStage<String> go() {
                executor.execute(() -> {
                    assertNotTimedOut("Waiting for the REST continuation", stage.callbackRegistered);
                    stage.completeExceptionally(new BadRequestException("Bad async result"));
                });
                return stage;
            }
        }

        server = ServerUtils.httpsServerForTest("http")
            .addHandler(restHandler(new Sample()))
            .start();

        try (Response response = call(request().url(server.uri().resolve("/samples").toString()))) {
            assertThat(response.code(), is(400));
            assertThat(response.body().string(), containsString("Bad async result"));
        }
    }

    @Test
    public void completedStagesDoNotResubmitFromTheApplicationWorker() throws Exception {
        var applicationExecutor = track(nonQueueingApplicationExecutor());

        @Path("samples")
        class Sample {
            @GET
            public CompletionStage<ThreadReportingEntity> go() {
                return CompletableFuture.completedFuture(new ThreadReportingEntity());
            }
        }

        server = ServerUtils.httpsServerForTest("http")
            .withHandlerExecutor(applicationExecutor)
            .withAsyncExecutor(applicationExecutor)
            .addHandler(restHandler(new Sample()).addCustomWriter(threadReportingWriter()))
            .start();

        try (Response response = call(request().url(server.uri().resolve("/samples").toString()))) {
            assertThat(response.code(), is(200));
            assertThat(response.body().string(), startsWith("application-"));
        }
    }

    @Test
    public void inlineResumeDoesNotResubmitFromTheApplicationWorker() throws Exception {
        var applicationExecutor = track(nonQueueingApplicationExecutor());

        @Path("samples")
        class Sample {
            @GET
            public void go(@Suspended AsyncResponse response) {
                assertThat(response.resume(new ThreadReportingEntity()), is(true));
            }
        }

        server = ServerUtils.httpsServerForTest("http")
            .withHandlerExecutor(applicationExecutor)
            .withAsyncExecutor(applicationExecutor)
            .addHandler(restHandler(new Sample()).addCustomWriter(threadReportingWriter()))
            .start();

        try (Response response = call(request().url(server.uri().resolve("/samples").toString()))) {
            assertThat(response.code(), is(200));
            assertThat(response.body().string(), startsWith("application-"));
        }
    }

    @Test
    public void timeoutResumeDoesNotResubmitFromTheApplicationWorker() throws Exception {
        var applicationExecutor = track(nonQueueingApplicationExecutor());
        var timerExecutor = track(Executors.newSingleThreadScheduledExecutor(namedThreads("timer-")));

        @Path("samples")
        class Sample {
            @GET
            public void go(@Suspended AsyncResponse response) {
                response.setTimeoutHandler(timedOut ->
                    timedOut.resume(new ThreadReportingEntity())
                );
                response.setTimeout(1, TimeUnit.MILLISECONDS);
            }
        }

        server = ServerUtils.httpsServerForTest("http")
            .withHandlerExecutor(applicationExecutor)
            .withAsyncExecutor(applicationExecutor)
            .withTimerExecutor(timerExecutor)
            .addHandler(restHandler(new Sample()).addCustomWriter(threadReportingWriter()))
            .start();

        try (Response response = call(request().url(server.uri().resolve("/samples").toString()))) {
            assertThat(response.code(), is(200));
            assertThat(response.body().string(), startsWith("application-"));
        }
    }


    @Test
    public void ifResumedWithExceptionThenItIsHandledNormally() throws Exception {
        @Path("samples")
        class Sample {
            @GET
            public void go(@Suspended AsyncResponse ar) {
                executor.submit(() -> {
                    ar.resume(new BadRequestException("Bad bad bad request"));
                });
            }
        }
        this.server = ServerUtils.httpsServerForTest().addHandler(restHandler(new Sample())).start();
        try (Response resp = call(request().url(server.uri().resolve("/samples").toString()))) {
            assertThat(resp.code(), is(400));
            assertThat(resp.header("content-type"), is("application/problem+json"));
            assertThat(resp.body().string(), containsString("\"title\":\"Bad bad bad request\""));
        }
    }


    @Test
    public void timeoutsCanBeSent() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch afterSentLatch = new CountDownLatch(1);
        Object[] afterTimeoutResult = new Object[1];
        @Path("samples")
        class Sample {
            @GET
            public void go(@Suspended AsyncResponse ar) {
                ar.setTimeout(10, TimeUnit.MILLISECONDS);
                assertNotTimedOut("Waiting until response finished", latch);
                afterTimeoutResult[0] = ar.resume("Hello");
                afterSentLatch.countDown();
            }
        }
        this.server = ServerUtils.httpsServerForTest().addHandler(restHandler(new Sample())).start();
        try (Response resp = call(request().url(server.uri().resolve("/samples").toString()))) {
            assertThat(resp.code(), is(503));
            assertThat(resp.body().string(), equalTo("<h1>503 Service Unavailable</h1><p>Timed out</p>"));
        }
        latch.countDown();
        assertNotTimedOut("Waiting until resume sent", afterSentLatch);
        assertThat(afterTimeoutResult[0], is(false));
    }


    @Test
    public void aCustomTimeoutHandlerCanBeUsed() throws Exception {
        class Hawk {
            String toHawker() {
                return "Oops, this is Hawkward";
            }
        }
        @Path("samples")
        class Sample {
            @GET
            public void go(@Suspended AsyncResponse ar) {
                ar.setTimeoutHandler(asyncResponse -> asyncResponse.resume(new Hawk()));
                ar.setTimeout(10, TimeUnit.MILLISECONDS);
            }
        }
        this.server = ServerUtils.httpsServerForTest()
            .addHandler(restHandler(new Sample())
                .addCustomWriter(new MessageBodyWriter<Hawk>() {
                    public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, jakarta.ws.rs.core.MediaType mediaType) {
                        return type.equals(Hawk.class);
                    }

                    public void writeTo(Hawk hawk, Class<?> type, Type genericType, Annotation[] annotations, jakarta.ws.rs.core.MediaType mediaType, MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException, WebApplicationException {
                        entityStream.write(hawk.toHawker().getBytes("UTF-8"));
                    }
                })
            )
            .start();
        try (Response resp = call(request().url(server.uri().resolve("/samples").toString()))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), equalTo("Oops, this is Hawkward"));
        }
    }


    @Test
    public void responsesCanBeCancelledWhichSendsA503() throws Exception {
        AtomicBoolean cancelResult = new AtomicBoolean();
        @Path("samples")
        class Sample {
            @GET
            public void go(@Suspended AsyncResponse ar, @QueryParam("retryDate") Long retryDate, @QueryParam("retrySeconds") Integer retrySeconds) {
                executor.submit(() -> {
                    if (retrySeconds != null) {
                        cancelResult.set(ar.cancel(retrySeconds));
                    } else if (retryDate != null) {
                        cancelResult.set(ar.cancel(new Date(retryDate)));
                    } else {
                        cancelResult.set(ar.cancel());
                    }
                });
            }
        }
        this.server = ServerUtils.httpsServerForTest().addHandler(restHandler(new Sample())).start();
        try (Response resp = call(request().url(server.uri().resolve("/samples").toString()))) {
            assertThat(resp.code(), is(503));
            assertThat(resp.header("Retry-After"), is(nullValue()));
        }
        try (Response resp = call(request().url(server.uri().resolve("/samples?retrySeconds=123").toString()))) {
            assertThat(resp.code(), is(503));
            assertThat(resp.header("Retry-After"), is("123"));
        }
        try (Response resp = call(request().url(server.uri().resolve("/samples?retryDate=1530959539077").toString()))) {
            assertThat(resp.code(), is(503));
            assertThat(resp.header("Retry-After"), is("Sat, 7 Jul 2018 10:32:19 GMT"));
        }
    }

    @Test
    public void onlyOneConcurrentResumeClaimsAnAsyncResponse() throws Exception {
        var asyncHandle = new QueuedAsyncHandle();
        var consumed = new AtomicInteger();
        var asyncResponse = new AsyncResponseAdapter(asyncHandle, ignored -> consumed.incrementAndGet());
        var callers = track(Executors.newFixedThreadPool(8));
        var start = new CountDownLatch(1);
        List<Future<Boolean>> attempts = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            attempts.add(callers.submit(() -> {
                assertNotTimedOut("Waiting to race resume calls", start);
                return asyncResponse.resume("response");
            }));
        }

        start.countDown();
        int successfulResumes = 0;
        for (Future<Boolean> attempt : attempts) {
            if (attempt.get(5, TimeUnit.SECONDS)) {
                successfulResumes++;
            }
        }

        assertThat(successfulResumes, is(1));
        assertThat(asyncHandle.applicationTasks.size(), is(1));
        assertThat(asyncResponse.isSuspended(), is(false));
        assertThat(asyncResponse.isDone(), is(false));

        asyncHandle.runNextApplicationTask();
        assertThat(consumed.get(), is(1));
        assertThat(asyncResponse.isDone(), is(true));
    }

    @Test
    public void queuedResumeDoesNotSerializeAfterExchangeCompletion() {
        var handle = new QueuedAsyncHandle();
        var consumed = new AtomicInteger();
        var response = new AsyncResponseAdapter(handle, ignored -> consumed.incrementAndGet());
        assertThat(response.resume("late result"), is(true));
        response.onComplete(new ResponseInfo() {
            public long duration() { return 0; }
            public boolean completedSuccessfully() { return true; }
            public MuRequest request() { throw new UnsupportedOperationException(); }
            public MuResponse response() { throw new UnsupportedOperationException(); }
        });
        handle.runNextApplicationTask();
        assertThat(consumed.get(), is(0));
        assertThat(response.isDone(), is(true));
    }

    @Test
    public void cancellationIsAtomicAndIdempotent() {
        var asyncHandle = new QueuedAsyncHandle();
        var sentResponse = new AtomicReference<Object>();
        var asyncResponse = new AsyncResponseAdapter(asyncHandle, sentResponse::set);

        assertThat(asyncResponse.cancel(), is(true));
        assertThat(asyncResponse.cancel(), is(true));
        assertThat(asyncResponse.resume("too late"), is(false));
        assertThat(asyncResponse.isCancelled(), is(true));
        assertThat(asyncResponse.isSuspended(), is(false));
        assertThat(asyncHandle.applicationTasks.size(), is(1));

        asyncHandle.runNextApplicationTask();
        assertThat(sentResponse.get(), instanceOf(jakarta.ws.rs.core.Response.class));
        assertThat(((jakarta.ws.rs.core.Response) sentResponse.get()).getStatus(), is(503));
        assertThat(asyncResponse.isDone(), is(true));
    }

    @Test
    public void nonPositiveTimeoutMeansSuspendIndefinitely() {
        var asyncHandle = new QueuedAsyncHandle();
        var asyncResponse = new AsyncResponseAdapter(asyncHandle, ignored -> {});

        assertThat(asyncResponse.setTimeout(0, TimeUnit.MILLISECONDS), is(true));
        assertThat(asyncResponse.setTimeout(-1, TimeUnit.SECONDS), is(true));

        assertThat(asyncHandle.delayedTasks.size(), is(0));
        assertThat(asyncResponse.isSuspended(), is(true));
    }

    @Test
    public void unresolvedCustomTimeoutFallsBackToA503() {
        var asyncHandle = new QueuedAsyncHandle();
        var sentResponse = new AtomicReference<Object>();
        var handlerInvocations = new AtomicInteger();
        var asyncResponse = new AsyncResponseAdapter(asyncHandle, sentResponse::set);
        asyncResponse.setTimeoutHandler(ignored -> handlerInvocations.incrementAndGet());

        assertThat(asyncResponse.setTimeout(1, TimeUnit.MILLISECONDS), is(true));
        asyncHandle.runNextDelayedTask();

        assertThat(handlerInvocations.get(), is(1));
        assertThat(asyncHandle.applicationTasks.size(), is(1));
        asyncHandle.runNextApplicationTask();
        assertThat(sentResponse.get(), instanceOf(ServiceUnavailableException.class));
        assertThat(asyncResponse.isDone(), is(true));
    }


    @Test
    public void completionCallbacksCanBeRegistered() {
        var responseCompleteHandler = new AtomicReference<ResponseCompleteListener>();
        AsyncHandle asyncHandle = new AsyncHandle() {
            public void setReadListener(RequestBodyListener readListener) {
                throw new UnsupportedOperationException();
            }
            public void complete() {
            }
            public void complete(Throwable throwable) {
            }
            public void write(ByteBuffer data, DoneCallback callback) {
                throw new UnsupportedOperationException();
            }
            public Future<Void> write(ByteBuffer data) {
                throw new UnsupportedOperationException();
            }
            public void addResponseCompleteHandler(ResponseCompleteListener listener) {
                responseCompleteHandler.set(listener);
            }
        };
        var asyncResponse = new AsyncResponseAdapter(asyncHandle, response -> {});
        var disconnected = new AtomicBoolean();
        var completed = new AtomicBoolean();

        Map<Class<?>, Collection<Class<?>>> registered = asyncResponse.register(
            (ConnectionCallback) ignored -> disconnected.set(true),
            (CompletionCallback) ignored -> completed.set(true)
        );

        assertThat(registered.values().stream().flatMap(Collection::stream).collect(toSet()),
            containsInAnyOrder(ConnectionCallback.class, CompletionCallback.class));
        responseCompleteHandler.get().onComplete(new ResponseInfo() {
            public long duration() {
                return 0;
            }
            public boolean completedSuccessfully() {
                return true;
            }
            public MuRequest request() {
                throw new UnsupportedOperationException();
            }
            public MuResponse response() {
                throw new UnsupportedOperationException();
            }
        });
        assertThat(disconnected.get(), is(false));
        assertThat(completed.get(), is(true));
    }

    @Test
    public void ifExceptionThrownAfterAsyncStartedButBeforeAsyncInvokedThenSomethingHappens() throws IOException {
        AtomicBoolean methodCalled = new AtomicBoolean(false);
        @Path("samples")
        class Sample {
            @POST
            public void echo(@Suspended AsyncResponse ar, int input) {
                methodCalled.set(true);
            }
        }
        this.server = ServerUtils.httpsServerForTest().addHandler(restHandler(new Sample())).start();
        try (Response resp = call(request()
            .post(RequestBody.create("", MediaType.parse("text/plain")))
            .url(server.uri().resolve("/samples").toString())
        )) {
            assertThat(resp.code(), equalTo(400));
            assertThat(resp.body().string(), containsString("\"status\":400"));
        }
        assertThat("Invalid request, but method was called", methodCalled.get(), is(false));
    }

    @AfterEach
    public void stop() {
        try {
            MuAssert.stopAndCheck(server);
        } finally {
            for (ExecutorService executorService : executors) {
                executorService.shutdownNow();
            }
        }
    }

    private <T extends ExecutorService> T track(T executorService) {
        executors.add(executorService);
        return executorService;
    }

    private static ThreadFactory namedThreads(String prefix) {
        var count = new AtomicInteger();
        return runnable -> new Thread(runnable, prefix + count.incrementAndGet());
    }

    private static ThreadPoolExecutor nonQueueingApplicationExecutor() {
        return new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(),
            namedThreads("application-")
        );
    }

    private static MessageBodyWriter<ThreadReportingEntity> threadReportingWriter() {
        return new MessageBodyWriter<ThreadReportingEntity>() {
            @Override
            public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations,
                                       jakarta.ws.rs.core.MediaType mediaType) {
                return type == ThreadReportingEntity.class;
            }

            @Override
            public void writeTo(ThreadReportingEntity entity, Class<?> type, Type genericType,
                                Annotation[] annotations, jakarta.ws.rs.core.MediaType mediaType,
                                MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream)
                throws IOException {
                entityStream.write(Thread.currentThread().getName().getBytes(StandardCharsets.UTF_8));
            }
        };
    }

    private static final class ThreadReportingEntity {
    }

    private static final class CallbackSignallingFuture<T> extends CompletableFuture<T> {
        private final CountDownLatch callbackRegistered = new CountDownLatch(1);

        @Override
        public CompletableFuture<T> whenComplete(
            BiConsumer<? super T, ? super Throwable> action
        ) {
            CompletableFuture<T> continuation = super.whenComplete(action);
            callbackRegistered.countDown();
            return continuation;
        }
    }

    private static final class QueuedAsyncHandle implements AsyncHandle {
        private final Queue<Runnable> applicationTasks = new ConcurrentLinkedQueue<>();
        private final Queue<Runnable> delayedTasks = new ConcurrentLinkedQueue<>();
        private final AtomicReference<ResponseCompleteListener> responseCompleteListener = new AtomicReference<>();

        @Override
        public void setReadListener(RequestBodyListener readListener) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void complete() {
        }

        @Override
        public void complete(Throwable throwable) {
        }

        @Override
        public void write(ByteBuffer data, DoneCallback callback) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<Void> write(ByteBuffer data) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void executeApplicationTask(Runnable task) {
            applicationTasks.add(task);
        }

        @Override
        public Future<?> scheduleApplicationTask(Runnable task, long delay, TimeUnit unit) {
            var delayed = new FutureTask<Void>(task, null);
            delayedTasks.add(delayed);
            return delayed;
        }

        @Override
        public void addResponseCompleteHandler(ResponseCompleteListener listener) {
            responseCompleteListener.set(listener);
        }

        private void runNextApplicationTask() {
            Objects.requireNonNull(applicationTasks.poll(), "No application task queued").run();
        }

        private void runNextDelayedTask() {
            Objects.requireNonNull(delayedTasks.poll(), "No delayed task queued").run();
        }
    }

}
