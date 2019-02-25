package io.muserver;

import javax.net.ssl.SSLContext;
import java.net.InetSocketAddress;
import java.net.URI;

class MuServerImpl implements MuServer {

	private final URI httpUri;
    private final URI httpsUri;
    private final Runnable shutdown;
    private final MuStats stats;
    private final InetSocketAddress address;
    private final SslContextProvider sslContextProvider;

    MuServerImpl(URI httpUri, URI httpsUri, Runnable shutdown, MuStats stats, InetSocketAddress address, SslContextProvider sslContextProvider) {
        this.stats = stats;
        this.address = address;
        this.sslContextProvider = sslContextProvider;
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
    public void changeSSLContext(SSLContext newSSLContext) {
        sslContextProvider.set(newSSLContext);
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
