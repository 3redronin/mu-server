package io.muserver;

import io.netty.handler.ssl.SslContext;
import org.jspecify.annotations.Nullable;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

class MuServerImpl implements MuServer {

    private @Nullable URI httpUri;
    private @Nullable URI httpsUri;
    private @Nullable Function<Duration, Boolean> shutdown;
    final MuStatsImpl stats;
    private @Nullable InetSocketAddress address;
    private @Nullable SslContextProvider sslContextProvider;
    private final Http2Config http2Config;
    private final ServerSettings settings;
    private final Set<HttpConnection> connections = ConcurrentHashMap.newKeySet();
    final @Nullable UnhandledExceptionHandler unhandledExceptionHandler;

    void onStarted(@Nullable URI httpUri, @Nullable URI httpsUri, Function<Duration, Boolean> shutdown,
                   InetSocketAddress address, @Nullable SslContextProvider sslContextProvider) {
        this.address = address;
        this.sslContextProvider = sslContextProvider;
        if (httpUri == null && httpsUri == null) {
            throw new IllegalArgumentException("One of httpUri and httpsUri must not be null");
        }
        this.httpUri = httpUri;
        this.httpsUri = httpsUri;
        this.shutdown = shutdown;
    }

    MuServerImpl(MuStatsImpl stats, @Nullable Http2Config http2Config, ServerSettings settings,
                 @Nullable UnhandledExceptionHandler unhandledExceptionHandler) {
        this.stats = stats;
        this.http2Config = http2Config == null ? Http2ConfigBuilder.http2Config().build() : http2Config;
        this.settings = settings;
        this.unhandledExceptionHandler = unhandledExceptionHandler;
    }

    Http2Config http2Config() {
        return http2Config;
    }


    @Override
    public boolean stop(long duration, TimeUnit unit) {
        return requireNonNull(shutdown, "Server has not started").apply(Duration.ofMillis(unit.toMillis(duration)));
    }

    @Override
    public URI uri() {
        return requireNonNull(httpsUri != null ? httpsUri : httpUri, "Server has not started");
    }

    @Override
    public @Nullable URI httpUri() {
        return httpUri;
    }

    @Override
    public @Nullable URI httpsUri() {
        return httpsUri;
    }

    @Override
    public MuStats stats() {
        return stats;
    }

    @Override
    public Set<HttpConnection> activeConnections() {
        return Collections.unmodifiableSet(connections);
    }

    @Override
    public InetSocketAddress address() {
        return requireNonNull(address, "Server has not started");
    }

    @Override
    public long minimumGzipSize() {
        return settings.minimumGzipSize;
    }

    @Override
    public int maxRequestHeadersSize() {
        return settings.maxHeadersSize;
    }

    @Override
    public long requestIdleTimeoutMillis() {
        return settings.requestReadTimeoutMillis;
    }

    @Override
    public long maxRequestSize() {
        return settings.maxRequestSize;
    }

    @Override
    public int maxUrlSize() {
        return settings.maxUrlSize;
    }

    @Override
    public boolean gzipEnabled() {
        return settings.gzipEnabled;
    }

    @Override
    public Set<String> mimeTypesToGzip() {
        return settings.mimeTypesToGzip;
    }


    @Override
    public void changeHttpsConfig(HttpsConfigBuilder newHttpsConfig) {
        Mutils.notNull("newSSLContext", newHttpsConfig);
        try {
            SslContext nettySslContext = newHttpsConfig.toNettySslContext(http2Config.enabled);
            SslContextProvider provider = requireNonNull(sslContextProvider, "Server does not have an HTTPS connector");
            provider.set(nettySslContext);
            ((SSLInfoImpl) provider.sslInfo()).setHttpsUri(httpsUri);
        } catch (Exception e) {
            throw new MuException("Error while changing SSL Certificate. The old one will still be used.", e);
        }
    }

    @Override
    public @Nullable SSLInfo sslInfo() {
        return sslContextProvider == null ? null : sslContextProvider.sslInfo();
    }

    @Override
    public List<RateLimiter> rateLimiters() {
        List<RateLimiterImpl> rateLimiters = settings.rateLimiters;
        return rateLimiters == null ? Collections.emptyList() : rateLimiters.stream().map(RateLimiter.class::cast).collect(Collectors.toList());
    }

    @Override
    public String toString() {
        return "MuServerImpl{" +
            "httpUri=" + httpUri +
            ", httpsUri=" + httpsUri +
            ", stats=" + stats +
            ", address=" + address +
            '}';
    }

    void onConnectionStarted(HttpConnection connection) {
        connections.add(connection);
    }

    void onConnectionEnded(HttpConnection connection) {
        connections.remove(connection);
    }

    ServerSettings settings() {
        return this.settings;
    }
}
