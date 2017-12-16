package ronin.muserver;

public enum HttpMethod {

	GET, POST, OPTIONS, PUT, DELETE, TRACE, CONNECT, PATCH;

	static HttpMethod fromNetty(io.netty.handler.codec.http.HttpMethod method) {
		if (method == io.netty.handler.codec.http.HttpMethod.GET) {
			return GET;
		}
		throw new RuntimeException("Unsupported method " + method);
	}

}
