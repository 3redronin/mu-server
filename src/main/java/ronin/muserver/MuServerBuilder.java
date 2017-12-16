package ronin.muserver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MuServerBuilder {
	public int httpPort = 0;
	private SyncHandlerAdapter handlers = new SyncHandlerAdapter();
	private AsyncMuHandler[] asyncHandlers = new AsyncMuHandler[0];

	public MuServerBuilder withHttpConnection(int port) {
		this.httpPort = port;
		return this;
	}
	public MuServerBuilder withAsyncHandlers(AsyncMuHandler... handlers) {
		this.asyncHandlers = handlers;
		return this;
	}

	public MuServerBuilder withHandlers(MuHandler... handlers) {
		this.handlers = new SyncHandlerAdapter(handlers);
		return this;
	}

	public MuServer build() {
		List<AsyncMuHandler> all = new ArrayList<>(Arrays.asList(asyncHandlers));
		all.add(handlers);
		return new MuServer(httpPort, all);
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
