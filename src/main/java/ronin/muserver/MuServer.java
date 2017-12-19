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

	public URL url() {
		return url;
	}
	public URI uri() {
		return uri;
	}
    public URI httpsUri() {
        return httpsUri;
    }
    public URL httpsUrl() {
        return httpsUrl;
    }
}
