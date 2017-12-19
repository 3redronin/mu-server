package ronin.muserver;

import java.net.URI;
import java.net.URL;

public class MuServer {

	private final URI uri;
	private final URL url;
    private final URI httpsUri;
    private final URL httpsUrl;
    private final Runnable shutdown;

	MuServer(URI uri, URL url, URI httpsUri, URL httpsUrl, Runnable shutdown) {
		this.uri = uri;
		this.url = url;
        this.httpsUri = httpsUri;
        this.httpsUrl = httpsUrl;
        this.shutdown = shutdown;
	}

	public void stop() {
		shutdown.run();
	}

    /**
     * @return The HTTP URL of the web server, if HTTP is supported
     */
	public URL url() {
		return url;
	}

    /**
     * @return The HTTP URI of the web server, if HTTP is supported
     */
    public URI uri() {
		return uri;
	}

    /**
     * @return The HTTPS URI of the web server, if HTTPS is supported
     */
    public URI httpsUri() {
        return httpsUri;
    }

    /**
     * @return The HTTPS URL of the web server, if HTTPS is supported
     */
    public URL httpsUrl() {
        return httpsUrl;
    }
}
