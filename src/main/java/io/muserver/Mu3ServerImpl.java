package io.muserver;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.muserver.GZIPEncoderBuilder.gzipEncoder;
import static java.util.Collections.emptyList;

class Mu3ServerImpl implements MuServer {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(Mu3ServerImpl.class);

    private final List<ConnectionAcceptor> acceptors;
    final List<MuHandler> handlers;
    private final List<ResponseCompleteListener> responseCompleteListeners;
    private final List<RequestRejectListener> requestRejectListeners;
    final UnhandledExceptionHandler exceptionHandler;
    final Long maxRequestBodySize;
    private final List<ContentEncoder> contentEncoders;
    private final Long requestIdleTimeoutMillis;
    private final Long idleTimeoutMillis;
    private final int maxUrlSize;
    private final int maxHeadersSize;
    final List<RateLimiterImpl> rateLimiters;
    final Path tempDir;
    private final ExecutionResources executionResources;
    private final RequestAdmission requestAdmission;
    private final ThreadLocal<ApplicationTaskContext> applicationTaskContext = new ThreadLocal<>();
    private final ApplicationTaskTracker detachedApplicationTasks = new ApplicationTaskTracker();
    private final ThreadLocal<@Nullable DetachedApplicationTask> activeDetachedApplicationTask =
        new ThreadLocal<>();
    private final Mu3StatsImpl statsImpl = new Mu3StatsImpl();

    Mu3ServerImpl(List<ConnectionAcceptor> acceptors, List<MuHandler> handlers, List<ResponseCompleteListener> responseCompleteListeners, List<RequestRejectListener> requestRejectListeners, UnhandledExceptionHandler exceptionHandler, Long maxRequestBodySize, List<ContentEncoder> contentEncoders, Long requestIdleTimeoutMillis, Long idleTimeoutMillis, int maxUrlSize, int maxHeadersSize, List<RateLimiterImpl> rateLimiters, Path tempDir, ExecutionResources executionResources, int maxConcurrentRequests) {
        this.acceptors = acceptors;
        this.handlers = handlers;
        this.responseCompleteListeners = responseCompleteListeners;
        this.requestRejectListeners = requestRejectListeners;
        this.exceptionHandler = exceptionHandler;
        this.maxRequestBodySize = maxRequestBodySize;
        this.contentEncoders = contentEncoders;
        this.requestIdleTimeoutMillis = requestIdleTimeoutMillis;
        this.idleTimeoutMillis = idleTimeoutMillis;
        this.maxUrlSize = maxUrlSize;
        this.maxHeadersSize = maxHeadersSize;
        this.rateLimiters = rateLimiters;
        this.tempDir = tempDir;
        this.executionResources = executionResources;
        this.requestAdmission = new RequestAdmission(maxConcurrentRequests);
    }

    private void startListening() {
        try {
            if (acceptors.isEmpty()) {
                throw new IllegalStateException("No listener ports defined");
            }
            for (ConnectionAcceptor acceptor : acceptors) {
                acceptor.start();
            }
        } catch (RuntimeException | Error startFailure) {
            try {
                stop(0, TimeUnit.MILLISECONDS);
            } catch (RuntimeException | Error cleanupFailure) {
                startFailure.addSuppressed(cleanupFailure);
            }
            throw startFailure;
        }
    }


    @Override
    public void stop() {
        stop(10, java.util.concurrent.TimeUnit.SECONDS);
    }

    @Override
    public boolean stop(long duration, java.util.concurrent.TimeUnit unit) {
        long timeoutMillis = Math.max(0L, unit.toMillis(duration));
        long deadlineNanos = MonotonicTime.deadlineAfterMillis(timeoutMillis);
        boolean stoppedCleanly = true;
        for (var acceptor : acceptors) {
            if (!acceptor.stopUntil(deadlineNanos)) {
                stoppedCleanly = false;
            }
        }
        if (!awaitDetachedApplicationTasks(deadlineNanos)) {
            stoppedCleanly = false;
        }
        executionResources.shutdown();
        return stoppedCleanly;
    }

    private boolean awaitDetachedApplicationTasks(long deadlineNanos) {
        // A callback cannot safely wait for other detached callbacks: with a
        // single-worker application executor, they may be queued behind this
        // callback and cannot start until stop() returns. Leave every registration
        // intact so a concurrent external stop caller still observes and waits for
        // the callback until its wrapper finishes.
        if (activeDetachedApplicationTask.get() != null) {
            return true;
        }
        return detachedApplicationTasks.awaitUntil(deadlineNanos);
    }

    void executeResponseCompletionTask(Runnable task) {
        executeTrackedApplicationTask(
            task,
            "response completion callback"
        );
    }

    @Nullable RejectedExecutionException executeTrackedApplicationTask(
        Runnable task,
        String description
    ) {
        DetachedApplicationTask registration = new DetachedApplicationTask();
        Runnable trackedTask = () -> {
            DetachedApplicationTask previous = activeDetachedApplicationTask.get();
            activeDetachedApplicationTask.set(registration);
            try {
                task.run();
            } finally {
                registration.deregister();
                if (previous == null) {
                    activeDetachedApplicationTask.remove();
                } else {
                    activeDetachedApplicationTask.set(previous);
                }
            }
        };
        RejectedExecutionException rejected;
        try {
            rejected = tryExecuteHandlerTask(trackedTask);
        } catch (RuntimeException | Error submissionFailure) {
            registration.deregister();
            throw submissionFailure;
        }
        if (rejected != null) {
            registration.deregister();
            log.warn("Dropping {} because its application executor rejected it", description, rejected);
        }
        return rejected;
    }

    private final class DetachedApplicationTask {
        private final ApplicationTaskTracker.Registration registration = detachedApplicationTasks.register();

        private void deregister() {
            registration.close();
        }
    }

    /** Nested application work is queued to avoid recursive callback delivery. */
    @Nullable RejectedExecutionException tryExecuteHandlerTask(Runnable task) {
        ApplicationTaskContext current = applicationTaskContext.get();
        if (current != null) {
            current.tasks.add(task);
            return null;
        }
        try {
            executionResources.application.execute(handlerApplicationTask(task));
            return null;
        } catch (RejectedExecutionException rejected) {
            return rejected;
        }
    }

    void executeAsyncApplicationTask(Runnable task) {
        RejectedExecutionException rejected = tryExecuteHandlerTask(task);
        if (rejected != null) throw rejected;
    }

    void executeInternalTask(Runnable task) {
        executionResources.internal.execute(task);
    }

    Runnable handlerApplicationTask(Runnable task) {
        Objects.requireNonNull(task, "task");
        return () -> runHandlerApplicationTask(task);
    }

    void runHandlerApplicationTask(Runnable task) {
        ApplicationTaskContext current = applicationTaskContext.get();
        if (current != null) {
            current.tasks.add(task);
            return;
        }
        ApplicationTaskContext context = new ApplicationTaskContext();
        context.tasks.add(task);
        applicationTaskContext.set(context);
        Throwable failure;
        try {
            failure = drainApplicationTasks(context, null);
        } finally {
            applicationTaskContext.remove();
        }
        if (failure != null) throwUnchecked(failure);
    }

    @SuppressWarnings("ReferenceEquality") // Throwable forbids suppressing itself; identity is required.
    private static @Nullable Throwable drainApplicationTasks(
        ApplicationTaskContext context,
        @Nullable Throwable failure
    ) {
        Runnable task;
        while ((task = context.tasks.poll()) != null) {
            try {
                task.run();
            } catch (Throwable taskFailure) {
                FatalErrors.rethrow(taskFailure);
                if (failure == null) {
                    failure = taskFailure;
                } else if (failure != taskFailure) {
                    failure.addSuppressed(taskFailure);
                }
            }
        }
        return failure;
    }

    private static void throwUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        if (failure instanceof Error) {
            throw (Error) failure;
        }
        throw new MuException("Application task failed", failure);
    }

    private static final class ApplicationTaskContext {
        private final Queue<Runnable> tasks = new ArrayDeque<>();
    }

    ExecutorService internalExecutor() {
        return executionResources.internal;
    }

    boolean tryAdmit(Mu3Request request) {
        RequestAdmission.Slot slot = requestAdmission.tryAcquire();
        if (slot == null) return false;
        request.admissionSlot = slot;
        return true;
    }

    @Override
    public URI uri() {
        var s = httpsUri();
        return s != null ? s : Objects.requireNonNull(httpUri(), "The server has no configured URI");
    }


    @Override
    public @Nullable URI httpUri() {
        return getUri(false);
    }

    @Override
    public @Nullable URI httpsUri() {
        return getUri(true);
    }

    @Nullable
    private URI getUri(boolean wantsHttps) {
        for (var acceptor : acceptors) {
            if (acceptor.isHttps() == wantsHttps) {
                return acceptor.uri();
            }
        }
        return null;
    }

    @Override
    public MuStats stats() {
        return statsImpl;
    }

    @Override
    public Set<HttpConnection> activeConnections() {

        if (acceptors.size() == 1) return Collections.unmodifiableSet(acceptors.get(0).activeConnections());
        var combined = new HashSet<HttpConnection>();
        for (ConnectionAcceptor acceptor : acceptors) {
            combined.addAll(acceptor.activeConnections());
        }
        return Collections.unmodifiableSet(combined);
    }

    @Override
    public InetSocketAddress address() {
        for (ConnectionAcceptor acceptor : acceptors) {
            return acceptor.address();
        }
        throw new IllegalStateException("No address available"); // not possible
    }


    private @Nullable GZIPEncoder zippy() {
        for (ConnectionAcceptor acceptor : acceptors) {
            for (ContentEncoder contentEncoder : acceptor.contentEncoders()) {
                if (contentEncoder instanceof GZIPEncoder) {
                    return (GZIPEncoder) contentEncoder;
                }
            }
        }
        return null;
    }

    @Override
    @Deprecated
    public long minimumGzipSize() {
        var enc = zippy();
        return enc == null ? 0L : enc.minGzipSize();
    }

    @Override
    public int maxRequestHeadersSize() {
        return maxHeadersSize;
    }

    @Override
    public long requestIdleTimeoutMillis() {
        return requestIdleTimeoutMillis;
    }

    @Override
    public long idleTimeoutMillis() {
        return idleTimeoutMillis;
    }

    @Override
    public long maxRequestSize() {
        return maxRequestBodySize;
    }

    @Override
    public int maxUrlSize() {
        return maxUrlSize;
    }

    @Override
    @Deprecated
    public boolean gzipEnabled() {
        return zippy() != null;
    }

    @Override
    public List<ContentEncoder> contentEncoders() {
        return contentEncoders;
    }

    @Override
    @Deprecated
    public Set<String> mimeTypesToGzip() {
        var enc = zippy();
        return enc == null ? Collections.emptySet() : enc.mimeTypesToGzip();
    }

    @Override
    public void changeHttpsConfig(HttpsConfig newHttpsConfig) {
        for (ConnectionAcceptor acceptor : acceptors) {
            if (acceptor.isHttps()) {
                acceptor.changeHttpsConfig(newHttpsConfig);
            }
        }
    }

    @Override
    public @Nullable HttpsConfig httpsConfig() {
        for (ConnectionAcceptor acceptor : acceptors) {
            if (acceptor.isHttps()) {
                return acceptor.httpsConfig();
            }
        }
        return null;
    }

    @Override
    public List<RateLimiter> rateLimiters() {
        return Collections.unmodifiableList(rateLimiters);
    }

    @Override
    public Path tempDir() {
        return tempDir;
    }

    @Override
    public List<MuHandler> handlers() {
        return handlers;
    }

    @Override
    public UnhandledExceptionHandler exceptionHandler() {
        return this.exceptionHandler;
    }

    @Override
    public long maxRequestBodySize() {
        return maxRequestBodySize;
    }


    void onRequestStarted(Mu3Request req) {
        statsImpl.onRequestStarted(req);
    }

    void onRequestSubmissionRejected(Mu3Request req) {
        req.releaseAdmission();
        statsImpl.onRequestSubmissionRejected(req);
    }

    void recordExchangeEnded(ResponseInfo exchange) {
        ((Mu3Request) exchange.request()).releaseAdmission();
        statsImpl.onRequestEnded(exchange);
    }

    void notifyExchangeEnded(ResponseInfo exchange) {
        for (var listener : responseCompleteListeners) {
            invokeResponseCompletionListener(listener, exchange);
        }
    }

    void invokeResponseCompletionListener(
        ResponseCompleteListener listener,
        ResponseInfo exchange
    ) {
        try {
            listener.onComplete(exchange);
        } catch (Throwable failure) {
            FatalErrors.rethrow(failure);
            log.error("Error from response completion listener", failure);
        }
    }

    void onRequestRejected(RejectedRequest info) {
        if (requestRejectListeners.isEmpty()) {
            return;
        }
        executeTrackedApplicationTask(
            () -> notifyRequestRejected(info),
            "request rejection notification"
        );
    }

    private void notifyRequestRejected(RejectedRequest info) {
        for (var listener : requestRejectListeners) {
            try {
                listener.onRejected(info);
            } catch (Throwable failure) {
                FatalErrors.rethrow(failure);
                log.error("Error from request reject listener", failure);
            }
        }
    }

    static MuServer start(MuServerBuilder builder) throws IOException {

        var exceptionHandler = UnhandledExceptionHandler.getDefault(builder.unhandledExceptionHandler());
        var tempDir = builder.tempDirectory();
        if (tempDir == null) {
            tempDir = Files.createTempDirectory("muservertemp");
        }

        var acceptors = new ArrayList<ConnectionAcceptor>(2);

        var actualHandlers = new ArrayList<MuHandler>();
        actualHandlers.add(RequestVerifierHandler.INSTANCE);
        if (builder.autoHandleExpectContinue()) {
            actualHandlers.add(0, new ExpectContinueHandler(builder.maxRequestSize()));
        }
        actualHandlers.addAll(builder.handlers());

        List<ContentEncoder> contentEncoders = builder.contentEncoders();
        if (contentEncoders == null) {
            contentEncoders = List.of(gzipEncoder().build());
        }

        List<RateLimiterImpl> limiters = builder.rateLimiters;
        if (limiters == null) {
            limiters = emptyList();
        }

        ExecutionResources resources = builder.executionResourcesFactory.create(builder.executor());
        ExecutorService handlerExecutor = resources.application;
        ExecutorService connectionExecutor = resources.connectionExecutor();
        ExecutorService http2WriterExecutor = resources.writerExecutor();

        var impl = new Mu3ServerImpl(
            acceptors,
            actualHandlers,
            builder.responseCompleteListeners(),
            builder.requestRejectListeners(),
            exceptionHandler,
            builder.maxRequestSize(),
            contentEncoders,
            builder.requestReadTimeoutMillis(),
            builder.idleTimeoutMills(),
            builder.maxUrlSize(),
            builder.maxHeadersSize(),
            limiters,
            tempDir,
            resources,
            builder.maxConcurrentRequests()
            );

        try {
            var ih = builder.interfaceHost();
            var address = ih == null ? null : InetAddress.getByName(ih);

            var configuredHttp2 = builder.http2Config();
            Http2Config http2ConfigForHttp = configuredHttp2;
            if (http2ConfigForHttp != null && http2ConfigForHttp.maxHeaderListSize() == -1) {
                http2ConfigForHttp = http2ConfigForHttp.toBuilder().withMaxHeaderListSize(builder.maxHeadersSize()).build();
            }

            if (builder.httpsPort() >= 0) {
                var http2Config = configuredHttp2;
                if (http2Config == null) {
                    http2Config = Http2ConfigBuilder.http2Config().withMaxHeaderListSize(builder.maxHeadersSize()).build();
                }
                if (http2Config.maxHeaderListSize() == -1) {
                    http2Config = http2Config.toBuilder().withMaxHeaderListSize(builder.maxHeadersSize()).build();
                }

                var httpsConfigBuilder = builder.httpsConfigBuilder();
                if (httpsConfigBuilder == null) {
                    httpsConfigBuilder = HttpsConfigBuilder.unsignedLocalhost();
                }
                var httpsConfig = httpsConfigBuilder.build3();

                var acceptor = ConnectionAcceptor.create(
                    impl,
                    address,
                    builder.httpsPort(),
                    httpsConfig,
                    http2Config,
                    handlerExecutor,
                    connectionExecutor,
                    http2WriterExecutor,
                    contentEncoders
                );
                acceptors.add(acceptor);
                httpsConfig.setHttpsUri(acceptor.uri());
            }
            if (builder.httpPort() >= 0) {
                acceptors.add(ConnectionAcceptor.create(
                    impl,
                    address,
                    builder.httpPort(),
                    null,
                    http2ConfigForHttp,
                    handlerExecutor,
                    connectionExecutor,
                    http2WriterExecutor,
                    contentEncoders
                ));
            }
            impl.startListening();
            return impl;
        } catch (IOException | RuntimeException | Error startFailure) {
            try {
                impl.stop(0, TimeUnit.MILLISECONDS);
            } catch (RuntimeException | Error cleanupFailure) {
                startFailure.addSuppressed(cleanupFailure);
            }
            throw startFailure;
        }
    }

    ScheduledFuture<?> scheduleConnectionTask(Runnable task, long delay, TimeUnit unit) {
        return executionResources.timer.schedule(() -> tryDispatchConnectionTask(task), delay, unit);
    }

    ScheduledFuture<?> scheduleTimerCallback(Runnable task, long delay, TimeUnit unit) {
        return executionResources.timer.schedule(task, delay, unit);
    }

    ScheduledFuture<?> scheduleConnectionTaskAtFixedRate(
        Runnable task,
        long initialDelay,
        long period,
        TimeUnit unit
    ) {
        var pending = new AtomicBoolean();
        return executionResources.timer.scheduleAtFixedRate(
            () -> {
                if (pending.compareAndSet(false, true)) {
                    boolean accepted = tryDispatchConnectionTask(() -> {
                        try {
                            task.run();
                        } finally {
                            pending.set(false);
                        }
                    });
                    if (!accepted) {
                        pending.set(false);
                    }
                }
            },
            initialDelay,
            period,
            unit
        );
    }

    private boolean tryDispatchConnectionTask(Runnable task) {
        try {
            executionResources.internal.execute(task);
            return true;
        } catch (RejectedExecutionException e) {
            log.debug("Connection maintenance executor rejected timed work because the server is stopping or overloaded");
            return false;
        }
    }

    public Mu3StatsImpl getStatsImpl() {
        return statsImpl;
    }
}
