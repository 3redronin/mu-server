package io.muserver.rest;

import io.muserver.MuServer;
import io.muserver.ResponseInfo;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.StreamingOutput;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.After;
import org.junit.Test;
import scaffolding.ClientUtils;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.request;
import static scaffolding.ServerUtils.httpsServerForTest;

public class CompletionStageFailureTest {
    static {
        MuRuntimeDelegate.ensureSet();
    }

    private final CompletableFuture<Object> result = new CompletableFuture<>();
    private final CountDownLatch invoked = new CountDownLatch(1);
    private final CountDownLatch inputClosed = new CountDownLatch(1);
    private final AtomicReference<InputStream> requestInput = new AtomicReference<>();
    private final AtomicInteger closes = new AtomicInteger();
    private final AtomicInteger completions = new AtomicInteger();
    private final CompletableFuture<ResponseInfo> completed = new CompletableFuture<>();
    private final OkHttpClient client = ClientUtils.client.newBuilder().callTimeout(3, TimeUnit.SECONDS).build();
    private MuServer server;

    @Path("stage")
    public class Resource {
        @POST
        public CompletionStage<Object> get(InputStream input) {
            requestInput.set(input);
            invoked.countDown();
            return result;
        }
    }

    @Test
    public void alreadyFailedStageProducesTheDefaultErrorResponse() throws Exception {
        result.completeExceptionally(new IllegalStateException("failed"));
        start(handler());
        try (Response response = call()) {
            assertThat(response.code(), equalTo(500));
            assertThat(response.header("Content-Type"), equalTo("application/problem+json"));
            assertThat(response.body().string(), containsString("\"status\":500"));
        }
        assertFinished();
    }

    @Test
    public void laterWrappedFailureIsMappedOnceUsingTheOriginalApplicationException() throws Exception {
        IllegalStateException original = new IllegalStateException("domain error", new IOException("underlying error"));
        AtomicInteger mappings = new AtomicInteger();
        AtomicReference<Throwable> mapped = new AtomicReference<>();
        start(handler().addExceptionMapper(IllegalStateException.class, e -> {
            mappings.incrementAndGet();
            mapped.set(e);
            return jakarta.ws.rs.core.Response.status(422).header("X-Mapped", "yes").entity("mapped body").build();
        }));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Response> responseFuture = executor.submit(this::call);
            assertThat(invoked.await(3, TimeUnit.SECONDS), equalTo(true));
            assertThat(completions.get(), equalTo(0));
            result.completeExceptionally(new CompletionException(new ExecutionException(original)));
            try (Response response = responseFuture.get(5, TimeUnit.SECONDS)) {
                assertThat(response.code(), equalTo(422));
                assertThat(response.header("X-Mapped"), equalTo("yes"));
                assertThat(response.body().string(), equalTo("mapped body"));
            }
            assertThat(mapped.get(), sameInstance(original));
            assertThat(mappings.get(), equalTo(1));
            assertFinished();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void wrappedNotFoundExceptionProduces404() throws Exception {
        result.completeExceptionally(new CompletionException(new NotFoundException("missing order")));
        start(handler());
        try (Response response = call()) {
            assertThat(response.code(), equalTo(404));
            assertThat(response.body().string(), containsString("missing order"));
        }
        assertFinished();
    }

    @Test
    public void webApplicationExceptionKeepsItsSuppliedResponse() throws Exception {
        result.completeExceptionally(new WebApplicationException(jakarta.ws.rs.core.Response.status(410)
            .header("X-Reason", "deleted").entity("order removed").build()));
        start(handler());
        try (Response response = call()) {
            assertThat(response.code(), equalTo(410));
            assertThat(response.header("X-Reason"), equalTo("deleted"));
            assertThat(response.body().string(), equalTo("order removed"));
        }
        assertFinished();
    }

    @Test
    public void cancelledStageCompletesWithAnErrorResponse() throws Exception {
        result.cancel(false);
        assertDefault500(handler());
    }

    @Test
    public void errorFailureIsPassedToItsMapperWithoutBeingCastToException() throws Exception {
        AssertionError original = new AssertionError("failed assertion");
        AtomicReference<Throwable> mapped = new AtomicReference<>();
        result.completeExceptionally(original);
        start(handler().addExceptionMapper(AssertionError.class, e -> {
            mapped.set(e);
            return jakarta.ws.rs.core.Response.serverError().entity("mapped assertion").build();
        }));
        try (Response response = call()) {
            assertThat(response.code(), equalTo(500));
            assertThat(response.body().string(), equalTo("mapped assertion"));
        }
        assertThat(mapped.get(), sameInstance(original));
        assertFinished();
    }

    @Test
    public void unmappedFailureUsesTheServerFallbackAndCompletes() throws Exception {
        result.completeExceptionally(new IllegalStateException("unmapped"));
        assertDefault500(handler().removeExceptionMapper(Throwable.class));
    }

    @Test
    public void unmappedErrorUsesTheServerFallbackAndCompletes() throws Exception {
        result.completeExceptionally(new AssertionError("unmapped"));
        assertDefault500(handler().removeExceptionMapper(Throwable.class));
    }

    @Test
    public void mapperFailureDoesNotLeaveTheExchangePending() throws Exception {
        AtomicInteger mappings = new AtomicInteger();
        result.completeExceptionally(new IllegalStateException("original"));
        assertDefault500(handler().addExceptionMapper(IllegalStateException.class, e -> {
            mappings.incrementAndGet();
            throw new IllegalArgumentException("mapper failed");
        }));
        assertThat(mappings.get(), equalTo(1));
    }

    @Test
    public void failureWritingTheMappedResponseDoesNotRunTheMapperAgain() throws Exception {
        AtomicInteger mappings = new AtomicInteger();
        result.completeExceptionally(new IllegalStateException("original"));
        assertDefault500(handler().addExceptionMapper(IllegalStateException.class, e -> {
            mappings.incrementAndGet();
            StreamingOutput entity = out -> { throw new IOException("writer failed"); };
            return jakarta.ws.rs.core.Response.status(422).type("text/plain").entity(entity).build();
        }));
        assertThat(mappings.get(), equalTo(1));
    }

    @Test
    public void failureAfterWritingDoesNotReplaceTheCommittedResponse() throws Exception {
        CountDownLatch allowWriterFailure = new CountDownLatch(1);
        result.completeExceptionally(new IllegalStateException("original"));
        start(handler().addExceptionMapper(IllegalStateException.class, e -> {
            StreamingOutput entity = out -> {
                out.write("partial".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.flush();
                try {
                    if (!allowWriterFailure.await(5, TimeUnit.SECONDS)) {
                        throw new IOException("Client did not consume the response prefix");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException(interrupted);
                }
                throw new IOException("writer failed after commit");
            };
            return jakarta.ws.rs.core.Response.status(422).type("text/plain").entity(entity).build();
        }));
        try (Response response = call()) {
            assertThat(response.code(), equalTo(422));
            assertThat(response.body().source().readUtf8(7), equalTo("partial"));
            allowWriterFailure.countDown();
            try {
                assertThat(response.body().string(), equalTo(""));
            } catch (IOException transportFailure) {
                // A writer failure may reset the HTTP/2 stream after the headers and prefix were sent.
                assertThat(transportFailure, not(instanceOf(InterruptedIOException.class)));
            }
        } finally {
            allowWriterFailure.countDown();
        }
        assertFinished();
    }

    @Test
    public void mappedResponseCanReadTheRequestStreamBeforeCleanup() throws Exception {
        result.completeExceptionally(new IllegalStateException("original"));
        start(handler().addExceptionMapper(IllegalStateException.class, e ->
            jakarta.ws.rs.core.Response.status(422).type("text/plain").entity(requestInput.get()).build()));
        try (Response response = call()) {
            assertThat(response.code(), equalTo(422));
            assertThat(response.body().string(), equalTo("request body"));
        }
        assertFinished();
    }

    @Test
    public void errorWhileWritingASuccessfulResultDoesNotDisappearIntoTheDependentFuture() throws Exception {
        StreamingOutput entity = out -> { throw new AssertionError("writer error"); };
        result.complete(entity);
        assertDefault500(handler());
    }

    @Test
    public void successfulNullResultStillProduces204() throws Exception {
        result.complete(null);
        start(handler());
        try (Response response = call()) {
            assertThat(response.code(), equalTo(204));
            assertThat(response.body().string(), equalTo(""));
        }
        assertFinished();
    }

    private RestHandlerBuilder handler() { return restHandler(new Resource()); }

    private void start(RestHandlerBuilder handler) {
        handler.addRequestFilter(context -> context.setEntityStream(new FilterInputStream(context.getEntityStream()) {
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                if (closes.get() > 0) throw new IOException("request input already closed");
                return in.read(bytes, offset, length);
            }

            @Override public void close() throws IOException {
                closes.incrementAndGet();
                try { super.close(); } finally { inputClosed.countDown(); }
            }
        }));
        server = httpsServerForTest().addHandler(handler).addResponseCompleteListener(info -> {
            completions.incrementAndGet();
            completed.complete(info);
        }).start();
    }

    private Response call() throws IOException {
        return client.newCall(request(server.uri().resolve("/stage"))
            .post(RequestBody.create("request body", MediaType.get("text/plain"))).build()).execute();
    }

    private void assertDefault500(RestHandlerBuilder handler) throws Exception {
        start(handler);
        try (Response response = call()) {
            assertThat(response.code(), equalTo(500));
            response.body().string();
        }
        assertFinished();
    }

    private void assertFinished() throws Exception {
        completed.get(5, TimeUnit.SECONDS);
        assertThat(inputClosed.await(5, TimeUnit.SECONDS), equalTo(true));
        assertThat(completions.get(), equalTo(1));
        assertThat(closes.get(), equalTo(1));
    }

    @After public void stop() {
        if (server != null) server.stop();
    }
}
