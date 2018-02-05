package io.muserver;

/**
 * An HTTP Method
 */
public enum Method {

	GET, POST, HEAD, OPTIONS, PUT, DELETE, TRACE, CONNECT, PATCH;

	static Method fromNetty(io.netty.handler.codec.http.HttpMethod method) {
		return Method.valueOf(method.name());
	}

}
