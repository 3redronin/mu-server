package io.muserver.rest;

import io.muserver.MuServer;
import jakarta.ws.rs.*;
import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.container.Suspended;
import jakarta.ws.rs.core.StreamingOutput;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.After;
import org.junit.Test;

import javax.xml.transform.Source;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;
import static scaffolding.ServerUtils.httpsServerForTest;

public class RequestEntityLifecycleTest {
    private MuServer server;
    private final AtomicReference<TrackedInputStream> input = new AtomicReference<>();

    @Path("echo")
    public static class Echo {
        @POST @Path("source") @Consumes("application/xml") @Produces("application/xml")
        public Source source(Source source) { return source; }

        @POST @Path("stream") @Produces("text/plain")
        public InputStream stream(InputStream stream) { return stream; }

        @POST @Path("reader") @Produces("text/plain")
        public Reader reader(Reader reader) { return reader; }

        @POST @Path("empty")
        public void empty() { }
    }

    @Test
    public void gzipFilteredXmlCanBeStreamedWithAndWithoutAnExplicitCharset() throws Exception {
        start(restHandler(new Echo()), true, false);
        for (String mediaType : new String[]{"application/xml", "application/xml;charset=utf-8"}) {
            try (Response response = post("echo/source", "<message>café</message>", mediaType, true)) {
                assertThat(response.code(), equalTo(200));
                assertThat(response.body().string(), containsString("<message>café</message>"));
            }
            assertClosed();
        }
    }

    @Test
    public void plainAndWrappedInputStreamsAndReadersStayOpenUntilWritten() throws Exception {
        for (boolean gzip : new boolean[]{false, true}) {
            start(restHandler(new Echo()), gzip, false);
            for (String path : new String[]{"echo/stream", "echo/reader"}) {
                try (Response response = post(path, "café", "text/plain;charset=utf-8", gzip)) {
                    assertThat(response.code(), equalTo(200));
                    assertThat(response.body().string(), equalTo("café"));
                }
                assertClosed();
                assertThat(input.get().closes.get(), equalTo(1));
            }
            server.stop();
        }
    }

    @Test
    public void writerFailureKeepsInputOpenForTheMappedResponseAndCleansUpOnce() throws Exception {
        AtomicInteger mappings = new AtomicInteger();
        @Path("failure")
        class Resource {
            @POST @Produces("text/plain")
            public StreamingOutput fail(InputStream stream) {
                return out -> { throw new IllegalStateException("writer failed"); };
            }
        }
        start(restHandler(new Resource()).addExceptionMapper(IllegalStateException.class, e -> {
            mappings.incrementAndGet();
            return jakarta.ws.rs.core.Response.status(422).type("text/plain").entity(input.get()).build();
        }), false, true);
        try (Response response = post("failure", "original body", "text/plain", false)) {
            assertThat(response.code(), equalTo(422));
            assertThat(response.body().string(), equalTo("original body"));
        }
        assertClosed();
        assertThat(input.get().closes.get(), equalTo(1));
        assertThat(mappings.get(), equalTo(1));
    }

    @Test
    public void emptyAndAbortedResponsesStillCloseTheReplacementStream() throws Exception {
        for (boolean abort : new boolean[]{false, true}) {
            RestHandlerBuilder handler = restHandler(new Echo());
            start(handler, false, false, abort);
            try (Response response = post("echo/empty", "unread", "text/plain", false)) {
                assertThat(response.code(), equalTo(abort ? 403 : 204));
            }
            assertClosed();
            assertThat(input.get().closes.get(), equalTo(1));
            server.stop();
        }
    }

    @Test
    public void successfulAsynchronousResponsesKeepInputOpenWhilePending() throws Exception {
        for (boolean suspended : new boolean[]{false, true}) {
            CompletableFuture<InputStream> result = new CompletableFuture<>();
            CompletableFuture<InputStream> invoked = new CompletableFuture<>();
            AtomicReference<AsyncResponse> async = new AtomicReference<>();
            @Path("async")
            class Resource {
                @POST @Path("stage") @Produces("text/plain")
                public CompletionStage<InputStream> stage(InputStream stream) {
                    invoked.complete(stream);
                    return result;
                }

                @POST @Path("suspended") @Produces("text/plain")
                public void suspended(InputStream stream, @Suspended AsyncResponse response) {
                    async.set(response);
                    invoked.complete(stream);
                }
            }
            start(restHandler(new Resource()), false, false);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<String> response = executor.submit(() -> {
                    try (Response r = post("async/" + (suspended ? "suspended" : "stage"), "async body", "text/plain", false)) {
                        assertThat(r.code(), equalTo(200));
                        return r.body().string();
                    }
                });
                InputStream stream = invoked.get(5, TimeUnit.SECONDS);
                assertThat(input.get().closes.get(), equalTo(0));
                if (suspended) {
                    assertThat(async.get().resume(stream), equalTo(true));
                } else {
                    result.complete(stream);
                }
                assertThat(response.get(5, TimeUnit.SECONDS), equalTo("async body"));
                assertClosed();
                assertThat(input.get().closes.get(), equalTo(1));
            } finally {
                executor.shutdownNow();
                server.stop();
            }
        }
    }

    private void start(RestHandlerBuilder handler, boolean gzip, boolean failOnClose) throws Exception {
        start(handler, gzip, failOnClose, false);
    }

    private void start(RestHandlerBuilder handler, boolean gzip, boolean failOnClose, boolean abort) throws Exception {
        handler.addRequestFilter(context -> {
            InputStream stream = context.getEntityStream();
            if (gzip) stream = new GZIPInputStream(stream);
            TrackedInputStream tracked = new TrackedInputStream(stream, failOnClose);
            input.set(tracked);
            context.setEntityStream(tracked);
            // Filters may remove stale metadata; stream ownership must survive this.
            context.getHeaders().remove("Content-Length");
            context.getHeaders().remove("Content-Encoding");
            if (abort) context.abortWith(jakarta.ws.rs.core.Response.status(403).build());
        });
        server = httpsServerForTest().addHandler(handler).start();
    }

    private Response post(String path, String body, String mediaType, boolean gzip) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (gzip) {
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            try (GZIPOutputStream out = new GZIPOutputStream(compressed)) { out.write(bytes); }
            bytes = compressed.toByteArray();
        }
        return call(request(server.uri().resolve("/" + path))
            .post(RequestBody.create(bytes, MediaType.get(mediaType))));
    }

    private void assertClosed() throws InterruptedException {
        assertThat("request input closed", input.get().closed.await(5, TimeUnit.SECONDS), equalTo(true));
    }

    private static class TrackedInputStream extends FilterInputStream {
        final AtomicInteger closes = new AtomicInteger();
        final CountDownLatch closed = new CountDownLatch(1);
        private final boolean failOnClose;

        TrackedInputStream(InputStream stream, boolean failOnClose) {
            super(stream);
            this.failOnClose = failOnClose;
        }

        private void checkOpen() throws IOException {
            if (closes.get() != 0) throw new IOException("Stream closed");
        }

        @Override public int read() throws IOException { checkOpen(); return in.read(); }
        @Override public int read(byte[] b, int off, int len) throws IOException { checkOpen(); return in.read(b, off, len); }

        @Override public void close() throws IOException {
            closes.incrementAndGet();
            try {
                super.close();
                if (failOnClose) throw new IOException("close failed");
            } finally {
                closed.countDown();
            }
        }
    }

    @After public void stop() {
        if (server != null) server.stop();
    }
}
