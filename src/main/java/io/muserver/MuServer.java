package io.muserver;

import java.net.URI;

/**
 * A web server handler. Create and start a web server by using {@link MuServerBuilder#httpsServer()} or
 * {@link MuServerBuilder#httpServer()}
 */
public class MuServer {

	private final URI httpUri;
    private final URI httpsUri;
    private final Runnable shutdown;

    MuServer(URI httpUri, URI httpsUri, Runnable shutdown) {
	    if (httpUri == null && httpsUri == null) {
            throw new IllegalArgumentException("One of httpUri and httpsUri must not be null");
        }
		this.httpUri = httpUri;
        this.httpsUri = httpsUri;
        this.shutdown = shutdown;
	}

	public void stop() {
		shutdown.run();
	}

    /**
     * @return The HTTPS (or if unavailable the HTTP) URI of the web server.
     */
    public URI uri() {
        return httpsUri != null ? httpsUri : httpUri;
    }

    /**
     * @return The HTTP URI of the web server, if HTTP is supported; otherwise null
     */
    public URI httpUri() {
		return httpUri;
	}

    /**
     * @return The HTTPS URI of the web server, if HTTPS is supported; otherwise null
     */
    public URI httpsUri() {
        return httpsUri;
    }

}
