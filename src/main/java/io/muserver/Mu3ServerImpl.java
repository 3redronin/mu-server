package io.muserver;

import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

import static io.muserver.GZIPEncoderBuilder.gzipEncoder;
import static java.util.Collections.emptyList;

class Mu3ServerImpl implements MuServer {

    private final List<ConnectionAcceptor> acceptors;
    final List<MuHandler> handlers;
    private final List<ResponseCompleteListener> responseCompleteListeners;
    final UnhandledExceptionHandler exceptionHandler;
    final Long maxRequestBodySize;
    private final List<ContentEncoder> contentEncoders;
    private final Long requestIdleTimeoutMillis;
    private final Long idleTimeoutMillis;
    private final int maxUrlSize;
    private final int maxHeadersSize;
    final List<RateLimiterImpl> rateLimiters;
    final Path tempDir;
    private final ExecutorService executorService;
    private final Mu3StatsImpl statsImpl = new Mu3StatsImpl();
    private final ScheduledExecutorService scheduledExecutor;

    Mu3ServerImpl(List<ConnectionAcceptor> acceptors, List<MuHandler> handlers, List<ResponseCompleteListener> responseCompleteListeners, UnhandledExceptionHandler exceptionHandler, Long maxRequestBodySize, List<ContentEncoder> contentEncoders, Long requestIdleTimeoutMillis, Long idleTimeoutMillis, int maxUrlSize, int maxHeadersSize, List<RateLimiterImpl> rateLimiters, Path tempDir, ExecutorService executorService) {
        this.acceptors = acceptors;
        this.handlers = handlers;
        this.responseCompleteListeners = responseCompleteListeners;
        this.exceptionHandler = exceptionHandler;
        this.maxRequestBodySize = maxRequestBodySize;
        this.contentEncoders = contentEncoders;
        this.requestIdleTimeoutMillis = requestIdleTimeoutMillis;
        this.idleTimeoutMillis = idleTimeoutMillis;
        this.maxUrlSize = maxUrlSize;
        this.maxHeadersSize = maxHeadersSize;
        this.rateLimiters = rateLimiters;
        this.tempDir = tempDir;
        this.executorService = executorService;
        this.scheduledExecutor = new OffloadingScheduledExecutorService(executorService);
    }

    private void startListening() {
        if (acceptors.isEmpty()) throw new IllegalStateException("No listener ports defined");
        for (ConnectionAcceptor acceptor : acceptors) {
            acceptor.start();
        }
    }


    @Override
    public void stop() {
        for (var acceptor : acceptors) {
            acceptor.stop(10000);
        }
    }

    @Override
    public URI uri() {
        var s = httpsUri();
        return s != null ? s : httpUri();
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
                return acceptor.getUri();
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
            return acceptor.getAddress();
        }
        throw new IllegalStateException("No address available"); // not possible
    }


    private @Nullable GZIPEncoder zippy() {
        for (ConnectionAcceptor acceptor : acceptors) {
            for (@NotNull ContentEncoder contentEncoder : acceptor.getContentEncoders()) {
                if (contentEncoder instanceof GZIPEncoder) {
                    return (GZIPEncoder) contentEncoder;
                }
            }
        }
        return null;
    }

    @Override
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
    public boolean gzipEnabled() {
        return zippy() != null;
    }

    @Override
    public List<ContentEncoder> contentEncoders() {
        return contentEncoders;
    }

    @Override
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
                return acceptor.getHttpsConfig();
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

    void onExchangeEnded(ResponseInfo exchange) {
        statsImpl.onRequestEnded(exchange);
        for (var listener : responseCompleteListeners) {
            listener.onComplete(exchange);
        }
    }

    static MuServer start(MuServerBuilder builder) throws IOException {

        var exceptionHandler = UnhandledExceptionHandler.getDefault(builder.unhandledExceptionHandler());
        ExecutorService executor = builder.executor();
        if (executor == null) {
            executor = MuServerBuilder.defaultExecutor();
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

        var tempDir = builder.tempDirectory();
        if (tempDir == null) {
            tempDir = Files.createTempDirectory("muservertemp");
        }

        List<RateLimiterImpl> limiters = builder.rateLimiters;
        if (limiters == null) {
            limiters = emptyList();
        }

        var impl = new Mu3ServerImpl(
            acceptors,
            actualHandlers,
            builder.responseCompleteListeners(),
            exceptionHandler,
            builder.maxRequestSize(),
            contentEncoders,
            builder.requestReadTimeoutMillis(),
            builder.idleTimeoutMills(),
            builder.maxUrlSize(),
            builder.maxHeadersSize(),
            limiters,
            tempDir,
            executor
            );

        var ih = builder.interfaceHost();
        var address = ih == null ? null : InetAddress.getByName(ih);

        if (builder.httpsPort() >= 0) {
            var http2Config = builder.http2Config();
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

            var acceptor = ConnectionAcceptor.create(impl, address, builder.httpsPort(), httpsConfig, http2Config, executor, contentEncoders);
            acceptors.add(acceptor);
            httpsConfig.setHttpsUri(acceptor.getUri());
        }
        if (builder.httpPort() >= 0) {
            acceptors.add(ConnectionAcceptor.create(impl, address, builder.httpPort(), null, null, executor, contentEncoders));
        }
        impl.startListening();
        return impl;
    }

    public ScheduledExecutorService getScheduledExecutor() {
        return scheduledExecutor;
    }

    public Mu3StatsImpl getStatsImpl() {
        return statsImpl;
    }
}
