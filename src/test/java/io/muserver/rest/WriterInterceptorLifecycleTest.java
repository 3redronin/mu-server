package io.muserver.rest;

import io.muserver.MuServer;
import jakarta.annotation.Priority;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.StreamingOutput;
import jakarta.ws.rs.ext.WriterInterceptor;
import jakarta.ws.rs.ext.WriterInterceptorContext;
import okhttp3.Response;
import org.junit.After;
import org.junit.Test;
import scaffolding.ClientUtils;

import java.io.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.request;
import static scaffolding.ServerUtils.httpsServerForTest;

public class WriterInterceptorLifecycleTest {
    static { MuRuntimeDelegate.ensureSet(); }

    private MuServer server;
    private StreamingOutput body = out -> out.write("body".getBytes(UTF_8));

    @Path("writer")
    @Produces("text/plain")
    public class Resource {
        @GET public StreamingOutput get() { return body; }
        @GET @Path("string") public String string() { return "body"; }
        @GET @Path("stage") public CompletionStage<StreamingOutput> stage() {
            return CompletableFuture.completedFuture(body);
        }
    }

    @Test
    public void priorityOrderedInterceptorsSurroundTheWriter() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        body = out -> {
            events.add("write");
            out.write("body".getBytes(UTF_8));
        };
        @Priority(100)
        class Outer implements WriterInterceptor {
            public void aroundWriteTo(WriterInterceptorContext context) throws IOException {
                events.add("outer-before");
                context.proceed();
                events.add("outer-after");
            }
        }
        @Priority(200)
        class Inner implements WriterInterceptor {
            public void aroundWriteTo(WriterInterceptorContext context) throws IOException {
                events.add("inner-before");
                context.proceed();
                events.add("inner-after");
            }
        }
        start(new Inner(), new Outer());
        assertBody("", "body");
        assertThat(events, contains("outer-before", "inner-before", "write", "inner-after", "outer-after"));
    }

    @Test
    public void gzipCanFinishAndRestoreTheStreamAfterProceed() throws Exception {
        start(context -> {
            context.getHeaders().putSingle("Content-Encoding", "gzip");
            OutputStream original = context.getOutputStream();
            GZIPOutputStream gzip = new GZIPOutputStream(original);
            context.setOutputStream(gzip);
            try {
                context.proceed();
            } finally {
                gzip.finish();
                context.setOutputStream(original);
            }
        });
        try (Response response = call("string")) {
            assertThat(response.code(), is(200));
            assertThat(response.header("Content-Encoding"), is("gzip"));
            assertThat(response.header("Content-Length"), nullValue());
            try (GZIPInputStream gzip = new GZIPInputStream(response.body().byteStream())) {
                assertThat(new String(gzip.readAllBytes(), UTF_8), is("body"));
            }
        }
    }

    @Test
    public void serializedBytesCanBeInspectedBeforeRestoringTheStream() throws Exception {
        start(context -> {
            OutputStream original = context.getOutputStream();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            context.setOutputStream(buffer);
            context.proceed();
            context.getHeaders().putSingle("X-Serialized-Bytes", buffer.size());
            context.setOutputStream(original);
            original.write(buffer.toByteArray());
        });
        try (Response response = call("")) {
            assertThat(response.code(), is(200));
            assertThat(response.header("X-Serialized-Bytes"), is("4"));
            assertThat(response.body().string(), is("body"));
        }
    }

    @Test
    public void suffixAfterProceedIsNotTruncatedByTheOriginalEntityLength() throws Exception {
        start(context -> {
            context.proceed();
            context.getOutputStream().write("-suffix".getBytes(UTF_8));
        });
        for (String path : List.of("string", "stage")) {
            assertBody(path, "body-suffix");
        }
    }

    @Test
    public void replacementStreamStaysOpenUntilAfterInterceptorsReturn() throws Exception {
        AtomicInteger closes = new AtomicInteger();
        start(context -> {
            context.setOutputStream(new FilterOutputStream(context.getOutputStream()) {
                @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                    if (closes.get() != 0) throw new IOException("closed too early");
                    out.write(bytes, offset, length);
                }
                @Override public void close() throws IOException {
                    closes.incrementAndGet();
                    super.close();
                }
            });
            context.proceed();
            context.getOutputStream().write("-suffix".getBytes(UTF_8));
        });
        assertBody("", "body-suffix");
        assertThat(closes.get(), is(1));
    }

    @Test
    public void omittingProceedCanReplaceTheBodyWithoutInvokingTheWriter() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        body = out -> { writes.incrementAndGet(); out.write("unwanted".getBytes(UTF_8)); };
        AtomicInteger laterCalls = new AtomicInteger();
        start(context -> context.getOutputStream().write("replacement".getBytes(UTF_8)),
            context -> { laterCalls.incrementAndGet(); context.proceed(); });
        assertBody("", "replacement");
        assertThat(writes.get(), is(0));
        assertThat(laterCalls.get(), is(0));
    }

    @Test
    public void omittingProceedCanSendAnEmptyResponseWithHeaders() throws Exception {
        start(context -> context.getHeaders().putSingle("X-Intercepted", "yes"));
        try (Response response = call("string")) {
            assertThat(response.code(), is(200));
            assertThat(response.header("X-Intercepted"), is("yes"));
            assertThat(response.body().string(), is(""));
        }
    }

    @Test
    public void interceptorCanCatchTheOriginalWriterException() throws Exception {
        IOException failure = new IOException("writer failed");
        body = out -> { throw failure; };
        start(context -> {
            try {
                context.proceed();
            } catch (IOException caught) {
                assertThat(caught, sameInstance(failure));
                throw new WebApplicationException(jakarta.ws.rs.core.Response.status(409).entity("caught by interceptor").build());
            }
        });
        try (Response response = call("")) {
            assertThat(response.code(), is(409));
            assertThat(response.body().string(), is("caught by interceptor"));
        }
    }

    @Test
    public void uncommittedWriterFailureUnwindsBeforeMappingAndClosesTheStream() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        body = out -> { events.add("write"); throw new IOException("writer failed"); };
        server = httpsServerForTest().withGzipEnabled(false).addHandler(restHandler(new Resource())
            .addExceptionMapper(IOException.class, failure -> {
                events.add("map");
                return jakarta.ws.rs.core.Response.status(422).entity("mapped").build();
            })
            .addWriterInterceptor(context -> {
                if (!(context.getEntity() instanceof StreamingOutput)) { context.proceed(); return; }
                context.setOutputStream(new FilterOutputStream(context.getOutputStream()) {
                    @Override public void close() throws IOException { events.add("close"); super.close(); }
                });
                try { context.proceed(); } finally { events.add("finally"); }
            })).start();
        try (Response response = call("")) {
            assertThat(response.code(), is(422));
            assertThat(response.body().string(), is("mapped"));
        }
        assertThat(events, contains("write", "finally", "close", "map"));
    }

    @Test
    public void aCheckedExceptionSetAsTheEntityStillReachesItsOriginalMapper() throws Exception {
        Exception original = new Exception("entity failure");
        server = httpsServerForTest().withGzipEnabled(false).addHandler(restHandler(new Resource())
            .addExceptionMapper(Exception.class, failure -> {
                assertThat(failure, sameInstance(original));
                return jakarta.ws.rs.core.Response.status(422).entity("mapped entity failure").build();
            })
            .addWriterInterceptor(context -> {
                if (context.getEntity() instanceof StreamingOutput) context.setEntity(original);
                context.proceed();
            })).start();
        try (Response response = call("")) {
            assertThat(response.code(), is(422));
            assertThat(response.body().string(), is("mapped entity failure"));
        }
    }

    @Test
    public void cleanupFailureIsSuppressedOnTheOriginalWriterFailure() throws Exception {
        IOException original = new IOException("writer failure");
        IOException cleanupFailure = new IOException("close failure");
        body = out -> { throw original; };
        server = httpsServerForTest().withGzipEnabled(false).addHandler(restHandler(new Resource())
            .addExceptionMapper(IOException.class, failure -> {
                assertThat(failure, sameInstance(original));
                assertThat(failure.getSuppressed(), arrayContaining(cleanupFailure));
                return jakarta.ws.rs.core.Response.status(422).entity("mapped writer failure").build();
            })
            .addWriterInterceptor(context -> {
                if (context.getEntity() instanceof StreamingOutput) {
                    context.setOutputStream(new FilterOutputStream(context.getOutputStream()) {
                        @Override public void close() throws IOException { throw cleanupFailure; }
                    });
                }
                context.proceed();
            })).start();
        try (Response response = call("")) {
            assertThat(response.code(), is(422));
            assertThat(response.body().string(), is("mapped writer failure"));
        }
    }

    @Test
    public void headRunsTheChainButDiscardsTheBodyAndSuffix() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        start(context -> {
            context.proceed();
            calls.incrementAndGet();
            context.getOutputStream().write("-suffix".getBytes(UTF_8));
        });
        try (Response response = ClientUtils.call(request(server.uri().resolve("/writer/string")).head())) {
            assertThat(response.code(), is(200));
            assertThat(response.body().string(), is(""));
        }
        assertThat(calls.get(), is(1));
    }

    @Test
    public void committedWriterFailureUnwindsAndCompletesTheAsyncExchange() throws Exception {
        CountDownLatch consumed = new CountDownLatch(1);
        CountDownLatch unwound = new CountDownLatch(1);
        CompletableFuture<Void> completed = new CompletableFuture<>();
        body = out -> {
            out.write("prefix".getBytes(UTF_8));
            out.flush();
            try {
                if (!consumed.await(5, TimeUnit.SECONDS)) throw new IOException("client did not read prefix");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
            throw new IOException("writer failed after commitment");
        };
        server = httpsServerForTest().withGzipEnabled(false).addHandler(restHandler(new Resource()).addWriterInterceptor(context -> {
            try { context.proceed(); } finally { unwound.countDown(); }
        })).addResponseCompleteListener(info -> completed.complete(null)).start();
        try (Response response = call("stage")) {
            assertThat(response.code(), is(200));
            assertThat(response.body().source().readUtf8(6), is("prefix"));
            assertThat(unwound.getCount(), is(1L));
            consumed.countDown();
            try {
                assertThat(response.body().string(), is(""));
            } catch (IOException failure) {
                assertThat(failure, not(instanceOf(InterruptedIOException.class)));
            }
        } finally { consumed.countDown(); }
        assertThat(unwound.await(5, TimeUnit.SECONDS), is(true));
        completed.get(5, TimeUnit.SECONDS);
    }

    private void start(WriterInterceptor... interceptors) {
        RestHandlerBuilder handler = restHandler(new Resource());
        for (WriterInterceptor interceptor : interceptors) handler.addWriterInterceptor(interceptor);
        server = httpsServerForTest().withGzipEnabled(false).addHandler(handler).start();
    }

    private Response call(String path) throws IOException {
        return ClientUtils.client.newBuilder().callTimeout(5, TimeUnit.SECONDS).build()
            .newCall(request(server.uri().resolve("/writer/" + path)).header("Accept-Encoding", "gzip").build()).execute();
    }

    private void assertBody(String path, String expected) throws IOException {
        try (Response response = call(path)) {
            assertThat(response.code(), is(200));
            assertThat(response.body().string(), is(expected));
        }
    }

    @After public void stop() { if (server != null) server.stop(); }
}
