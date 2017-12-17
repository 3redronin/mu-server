package ronin.muserver;

import java.util.ArrayList;
import java.util.List;

public class MuServerBuilder {
	public int httpPort = 0;
	private List<AsyncMuHandler> asyncHandlers = new ArrayList<>();
	private List<MuHandler> handlers = new ArrayList<>();

	public MuServerBuilder withHttpConnection(int port) {
		this.httpPort = port;
		return this;
	}
	public MuServerBuilder addAsyncHandler(AsyncMuHandler handler) {
		asyncHandlers.add(handler);
		return this;
	}

	public MuServerBuilder addHandler(MuHandler handler) {
		handlers.add(handler);
		return this;
	}
	public MuServerBuilder addHandler(HttpMethod method, String pathRegex, MuHandler handler) {
		return addHandler(Routes.route(method, pathRegex, handler));
	}


	public MuServer build() {
		if (!handlers.isEmpty()) {
			asyncHandlers.add(new SyncHandlerAdapter(handlers));
		}
		return new MuServer(httpPort, asyncHandlers);
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
