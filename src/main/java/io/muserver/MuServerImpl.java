package io.muserver;

import javax.net.ssl.SSLContext;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Set;

class MuServerImpl implements MuServer {

    private final URI httpUri;
    private final URI httpsUri;
    private final Runnable shutdown;
    private final MuStats stats;
    private final InetSocketAddress address;
    private final SslContextProvider sslContextProvider;
    private final boolean http2Enabled;
    private final ServerSettings settings;

    MuServerImpl(URI httpUri, URI httpsUri, Runnable shutdown, MuStats stats, InetSocketAddress address, SslContextProvider sslContextProvider, boolean http2Enabled, ServerSettings settings) {
        this.stats = stats;
        this.address = address;
        this.sslContextProvider = sslContextProvider;
        this.http2Enabled = http2Enabled;
        this.settings = settings;
        if (httpUri == null && httpsUri == null) {
            throw new IllegalArgumentException("One of httpUri and httpsUri must not be null");
        }
        this.httpUri = httpUri;
        this.httpsUri = httpsUri;
        this.shutdown = shutdown;
    }

    @Override
    public void stop() {
        shutdown.run();
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
    public void changeSSLContext(SSLContext newSSLContext) {
        changeSSLContext(SSLContextBuilder.sslContext().withSSLContext(newSSLContext));
    }

    @Override
    public void changeSSLContext(SSLContextBuilder newSSLContext) {
        Mutils.notNull("newSSLContext", newSSLContext);
        try {
            sslContextProvider.set(newSSLContext.toNettySslContext(http2Enabled));
        } catch (Exception e) {
            throw new MuException("Error while changing SSL Certificate. The old one will still be used.", e);
        }
    }

    @Override
    public SSLInfo sslInfo() {
        return sslContextProvider == null ? null : sslContextProvider.sslInfo();
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
}
