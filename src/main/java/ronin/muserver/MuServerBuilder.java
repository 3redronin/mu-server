package ronin.muserver;

public class MuServerBuilder {
	public int httpPort = 0;

	public MuServerBuilder withHttpConnection(int port) {
		this.httpPort = port;
		return this;
	}

	public MuServer build() {
		return new MuServer(httpPort);
	}

	public static MuServerBuilder muServer() {
		return new MuServerBuilder();
	}
}
