package ronin.muserver;

public enum HttpMethod {

	GET, POST, OPTIONS, PUT, DELETE, TRACE, CONNECT, PATCH;

	static HttpMethod fromNetty(io.netty.handler.codec.http.HttpMethod method) {
		return HttpMethod.valueOf(method.name());
	}

}
