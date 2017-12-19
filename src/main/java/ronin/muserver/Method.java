package ronin.muserver;

/**
 * An HTTP Method
 */
public enum Method {

	GET, POST, OPTIONS, PUT, DELETE, TRACE, CONNECT, PATCH;

	static Method fromNetty(io.netty.handler.codec.http.HttpMethod method) {
		return Method.valueOf(method.name());
	}

}
