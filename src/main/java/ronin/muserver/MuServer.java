package ronin.muserver;

import java.net.URI;
import java.net.URL;

public class MuServer {

	private final URI uri;
	private final URL url;
	private final Runnable shutdown;

	MuServer(URI uri, URL url, Runnable shutdown) {
		this.uri = uri;
		this.url = url;
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
}
