package validation;

import io.muserver.*;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Benchmark only: identical outbound client and bounded response write in both handler styles. */
public final class PerformanceFixture {
    private static final AtomicLong handled = new AtomicLong();
    private static final AtomicLong started = new AtomicLong();
    private static final AtomicLong downstreamSuccess = new AtomicLong();
    private static final AtomicLong failures = new AtomicLong();
    private static final AtomicLong timeouts = new AtomicLong();
    private static final AtomicLong cancellations = new AtomicLong();
    private static final AtomicLong responses = new AtomicLong();
    private static final AtomicLong disconnected = new AtomicLong();
    private static final AtomicLong pending = new AtomicLong(), peakPending = new AtomicLong();
    private static final AtomicLong waiting = new AtomicLong(), peakWaiting = new AtomicLong();
    private static final Set<Operation> operations = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean stopping = new AtomicBoolean();
    private static byte[] payload;
    private static HttpClient client;

    private static void enter(AtomicLong current, AtomicLong peak) {
        peak.accumulateAndGet(current.incrementAndGet(), Math::max);
    }

    private static final class Operation {
        final AsyncHandle handle;
        final MuResponse response;
        final AtomicBoolean claimed = new AtomicBoolean();
        final AtomicBoolean completing = new AtomicBoolean();
        final AtomicReference<CompletableFuture<HttpResponse<byte[]>>> outbound = new AtomicReference<>();

        Operation(MuRequest request, MuResponse response) {
            this.response = response;
            this.handle = request.handleAsync();
            operations.add(this);
            handle.addResponseCompleteHandler(info -> {
                claimed.set(true);
                if (info.completedSuccessfully()) responses.incrementAndGet();
                else disconnected.incrementAndGet();
                cancel();
                operations.remove(this);
            });
        }

        void cancel() {
            CompletableFuture<?> future = outbound.get();
            if (future != null) future.cancel(true);
        }

        void complete(Throwable failure) {
            if (completing.compareAndSet(false, true)) handle.complete(failure);
        }

        void finish(int status, byte[] body, Throwable failure) {
            if (!claimed.compareAndSet(false, true)) return;
            try {
                if (failure != null) { complete(failure); return; }
                response.status(status);
                response.headers().set("Content-Length", body.length);
                // One finite write; no queue of writes or blocking output API in either style.
                handle.write(ByteBuffer.wrap(body), this::complete);
            } catch (Exception error) {
                complete(error);
            }
        }

        void result(HttpResponse<byte[]> result, Throwable error) {
            while ((error instanceof CompletionException || error instanceof ExecutionException)
                    && error.getCause() != null) error = error.getCause();
            if (error instanceof CancellationException) {
                finish(502, null, error);
            } else if (error != null) {
                finish(error instanceof HttpTimeoutException || error instanceof TimeoutException ? 504 : 502, payload, null);
            } else if (result.statusCode() != 200 || !Arrays.equals(result.body(), payload)) {
                finish(502, payload, null);
            } else {
                finish(200, result.body(), null);
            }
        }

        void run(URI uri, boolean block) {
            if (stopping.get()) { finish(503, payload, null); return; }
            started.incrementAndGet();
            enter(pending, peakPending);
            CompletableFuture<HttpResponse<byte[]>> future;
            try {
                future = client.sendAsync(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(2))
                    .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            } catch (Exception failure) {
                pending.decrementAndGet(); failures.incrementAndGet(); result(null, failure); return;
            }
            outbound.set(future);
            // HttpRequest.timeout alone does not bound body consumption on every runtime.
            // Time out a dependent future, then cancel the original exchange; timing out
            // the original future would complete it without cancelling its socket work.
            CompletableFuture<HttpResponse<byte[]>> bounded = future.thenApply(reply -> reply)
                .orTimeout(2, TimeUnit.SECONDS);
            // Register accounting before either style can consume the result. It runs once, including cancellation.
            bounded.whenComplete((reply, error) -> {
                pending.decrementAndGet();
                Throwable cause = error;
                while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
                if (cause instanceof CancellationException) cancellations.incrementAndGet();
                else if (cause instanceof HttpTimeoutException || cause instanceof TimeoutException) {
                    timeouts.incrementAndGet(); cancel();
                }
                else if (cause != null || reply.statusCode() != 200 || !Arrays.equals(reply.body(), payload)) failures.incrementAndGet();
                else downstreamSuccess.incrementAndGet();
            });
            // Cover termination or shutdown before publication of the future.
            if (claimed.get() || stopping.get()) cancel();
            if (block) {
                enter(waiting, peakWaiting);
                HttpResponse<byte[]> reply = null;
                Throwable failure = null;
                try { reply = bounded.get(); }
                catch (InterruptedException interrupted) {
                    cancel(); Thread.currentThread().interrupt(); failure = interrupted;
                } catch (ExecutionException | CancellationException error) { failure = error; }
                finally { waiting.decrementAndGet(); }
                result(reply, failure);
            } else {
                bounded.whenComplete(this::result);
            }
        }
    }

    private static String metrics() {
        return "{\"started\":" + started + ",\"downstream_success\":" + downstreamSuccess
            + ",\"failures\":" + failures + ",\"timeouts\":" + timeouts + ",\"cancellations\":" + cancellations
            + ",\"responses\":" + responses + ",\"disconnected\":" + disconnected
            + ",\"pending\":" + pending + ",\"peak_pending\":" + peakPending
            + ",\"waiting_handlers\":" + waiting + ",\"peak_waiting_handlers\":" + peakWaiting
            + ",\"active_responses\":" + operations.size() + "}";
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> settings = new HashMap<>();
        for (String arg : args) { String[] pair = arg.split("=", 2); settings.put(pair[0], pair[1]); }
        payload = Arrays.copyOf(Files.readAllBytes(Path.of(settings.get("payloads"), "high.bin")), 128);
        AtomicLong clientThread = new AtomicLong();
        ExecutorService executor = Executors.newFixedThreadPool(8, task ->
            new Thread(task, "downstream-http-" + clientThread.incrementAndGet()));
        client = HttpClient.newBuilder().executor(executor).connectTimeout(Duration.ofSeconds(1))
            .version(HttpClient.Version.HTTP_1_1).build();
        String style = System.getProperty("downstream.style", "async");
        if (!List.of("direct", "blocking", "async").contains(style)) throw new IllegalArgumentException("style");
        String downstream = System.getProperty("downstream.url");
        String protocol = settings.get("protocol");
        MuServerBuilder builder = MuServerBuilder.muServer().withInterface("127.0.0.1");
        if (protocol.equals("http1")) builder.withHttpPort(0);
        else {
            builder.withHttpsPort(0).withHttpsConfig(HttpsConfigBuilder.httpsConfig()
                .withKeystore(new File(settings.get("keystore"))).withKeystoreType("PKCS12")
                .withKeystorePassword("validation").withKeyPassword("validation").withProtocols("TLSv1.3")
                .withCipherFilter((supported, defaults) -> List.of("TLS_AES_128_GCM_SHA256")));
            builder.withHttp2Config(Http2ConfigBuilder.http2Config().enabled(protocol.equals("h2")));
        }
        String mode = System.getProperty("comparison.threading", "AUTO");
        if (!mode.equals("AUTO")) {
            Class<?> type = Class.forName("io.muserver.ThreadingMode");
            builder.getClass().getMethod("withThreadingMode", type).invoke(builder, type.getField(mode).get(null));
        }
        String transport = "NETTY";
        try {
            java.lang.reflect.Field field = builder.getClass().getDeclaredField("useChannelTransport");
            field.setAccessible(true); transport = field.getBoolean(builder) ? "NIO" : "SOCKET";
        } catch (NoSuchFieldException oldTransport) {
            try { Class.forName("io.muserver.ThreadingMode"); transport = "SOCKET"; }
            catch (ClassNotFoundException netty) { }
        }
        final String observedTransport = transport;
        try { builder.getClass().getMethod("withTempDirectory", Path.class).invoke(builder, Path.of(settings.get("uploads"))); }
        catch (NoSuchMethodException legacy) { }
        builder.addHandler((request, response) -> {
            handled.incrementAndGet(); response.contentType("text/plain");
            String route = request.relativePath();
            if (route.equals("/mode")) {
                boolean virtual = false;
                try { virtual = (boolean) Thread.class.getMethod("isVirtual").invoke(Thread.currentThread()); }
                catch (NoSuchMethodException older) { }
                response.write(mode + ":" + virtual + ":" + observedTransport);
            } else if (route.equals("/download") || route.equals("/local")) {
                Operation op = new Operation(request, response);
                if (route.equals("/local") || style.equals("direct")) op.finish(200, payload, null);
                else {
                    // Fault paths are explicit test-only inputs to the loopback fixture.
                    String fault = request.query().get("fault");
                    URI uri = URI.create("refusal".equals(fault) ? System.getProperty("downstream.refusedUrl")
                        : downstream + (fault == null ? "" : "&fault=" + fault));
                    op.run(uri, style.equals("blocking"));
                }
            } else { response.status(404); response.write("missing"); }
            return true;
        });
        MuServer server = builder.start();
        String gc = ManagementFactory.getGarbageCollectorMXBeans().stream()
            .map(bean -> bean.getName().replace(' ', '_')).reduce((a, b) -> a + "," + b).orElse("unknown");
        System.out.println("READY " + ProcessHandle.current().pid() + " " + server.uri().getPort()
            + " " + Runtime.getRuntime().maxMemory() + " " + Runtime.getRuntime().availableProcessors() + " " + gc);
        System.out.flush();
        try (BufferedReader commands = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String command;
            while ((command = commands.readLine()) != null && !command.equals("STOP")) {
                if (command.equals("METRICS")) System.out.println("METRICS " + metrics());
                else if (command.equals("RESET_PEAKS")) {
                    peakPending.set(pending.get()); peakWaiting.set(waiting.get()); System.out.println("RESET_PEAKS");
                } else if (command.equals("STATS")) {
                    long buffers = ManagementFactory.getPlatformMXBeans(java.lang.management.BufferPoolMXBean.class)
                        .stream().mapToLong(java.lang.management.BufferPoolMXBean::getMemoryUsed).sum();
                    long collections = ManagementFactory.getGarbageCollectorMXBeans().stream()
                        .mapToLong(bean -> Math.max(0, bean.getCollectionCount())).sum();
                    long millis = ManagementFactory.getGarbageCollectorMXBeans().stream()
                        .mapToLong(bean -> Math.max(0, bean.getCollectionTime())).sum();
                    System.out.println("STATS " + handled + " " + ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed()
                        + " " + ManagementFactory.getThreadMXBean().getThreadCount() + " " + buffers + " " + collections + " " + millis);
                } else throw new IllegalArgumentException("Unknown control command");
                System.out.flush();
            }
        } finally {
            stopping.set(true);
            for (Operation op : operations) op.cancel();
            server.stop();
            // Compile with --release 11, but use the runtime's explicit HTTP client shutdown on 21/25.
            try { HttpClient.class.getMethod("shutdownNow").invoke(client); }
            catch (NoSuchMethodException older) { }
            executor.shutdownNow();
            if (!executor.awaitTermination(3, TimeUnit.SECONDS)) throw new IllegalStateException("Client executor did not terminate");
            System.out.println("FINAL_METRICS " + metrics());
            System.out.println("STOPPED"); System.out.flush();
        }
    }
}
