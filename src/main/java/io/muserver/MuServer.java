package io.muserver;

import java.net.URI;
import java.net.URL;

/**
 * A web server handler. Create and start a web server by using {@link MuServerBuilder#httpsServer()} or
 * {@link MuServerBuilder#httpServer()}
 */
public class MuServer {

	private final URI httpUri;
	private final URL httpUrl;
    private final URI httpsUri;
    private final URL httpsUrl;
    private final Runnable shutdown;

    MuServer(URI httpUri, URL httpUrl, URI httpsUri, URL httpsUrl, Runnable shutdown) {
	    if (httpUri == null && httpsUri == null) {
            throw new IllegalArgumentException("One of httpUri and httpsUri must not be null");
        }
		this.httpUri = httpUri;
		this.httpUrl = httpUrl;
        this.httpsUri = httpsUri;
        this.httpsUrl = httpsUrl;
        this.shutdown = shutdown;
	}

	public void stop() {
		shutdown.run();
	}

    /**
     * @return The HTTPS (or if unavailable the HTTP) URL of the web server.
     */
    public URL url() {
        return httpsUrl != null ? httpsUrl : httpUrl;
    }

    /**
     * @return The HTTPS (or if unavailable the HTTP) URI of the web server.
     */
    public URI uri() {
        return httpsUri != null ? httpsUri : httpUri;
    }


    /**
     * @return The HTTP URL of the web server, if HTTP is supported; otherwise null
     */
	public URL httpUrl() {
		return httpUrl;
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

    /**
     * @return The HTTPS URL of the web server, if HTTPS is supported; otherwise null
     */
    public URL httpsUrl() {
        return httpsUrl;
    }
}
