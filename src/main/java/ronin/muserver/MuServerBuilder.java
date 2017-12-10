package ronin.muserver;

public class MuServerBuilder {
	public int httpPort = 0;
	private MuHandler[] handlers;

	public MuServerBuilder withHttpConnection(int port) {
		this.httpPort = port;
		return this;
	}

	public MuServerBuilder withHandlers(MuHandler... handlers) {
		this.handlers = handlers;
		return this;
	}

	public MuServer build() {
		return new MuServer(httpPort, handlers);
	}

	public MuServer start() throws InterruptedException {
		MuServer server = build();
		server.start();
		return server;
	}

	public static MuServerBuilder muServer() {
		return new MuServerBuilder();
	}
}
