package io.muserver;

import io.netty.handler.ssl.SslContext;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

class MuServerImpl implements MuServer {

    private URI httpUri;
    private URI httpsUri;
    private Consumer<Duration> shutdown;
    final MuStatsImpl stats;
    private InetSocketAddress address;
    private SslContextProvider sslContextProvider;
    private final boolean http2Enabled;
    private final ServerSettings settings;
    private final Set<HttpConnection> connections = ConcurrentHashMap.newKeySet();
    final UnhandledExceptionHandler unhandledExceptionHandler;

    void onStarted(URI httpUri, URI httpsUri, Consumer<Duration> shutdown, InetSocketAddress address, SslContextProvider sslContextProvider) {
        this.address = address;
        this.sslContextProvider = sslContextProvider;
        if (httpUri == null && httpsUri == null) {
            throw new IllegalArgumentException("One of httpUri and httpsUri must not be null");
        }
        this.httpUri = httpUri;
        this.httpsUri = httpsUri;
        this.shutdown = shutdown;
    }

    MuServerImpl(MuStatsImpl stats, boolean http2Enabled, ServerSettings settings, UnhandledExceptionHandler unhandledExceptionHandler) {
        this.stats = stats;
        this.http2Enabled = http2Enabled;
        this.settings = settings;
        this.unhandledExceptionHandler = unhandledExceptionHandler;
    }


    @Override
    public void stop(long duration, TimeUnit unit) {
        shutdown.accept(Duration.ofMillis(unit.toMillis(duration)));
    }

    @Override
    public URI uri() {
        return httpsUri != null ? httpsUri : httpUri;
    }

    @Override
    public URI httpUri() {
        return httpUri;
    }

    @Override
    public URI httpsUri() {
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
        return address;
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
            SslContext nettySslContext = newHttpsConfig.toNettySslContext(http2Enabled);
            sslContextProvider.set(nettySslContext);
            ((SSLInfoImpl) sslContextProvider.sslInfo()).setHttpsUri(httpsUri);
        } catch (Exception e) {
            throw new MuException("Error while changing SSL Certificate. The old one will still be used.", e);
        }
    }

    @Override
    public SSLInfo sslInfo() {
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
