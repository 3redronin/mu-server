package ronin.muserver;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;

import java.net.URI;

public interface MuRequest {

	HttpMethod method();
	URI uri();
	URI serverURI();
	Headers headers();

}

class NettyRequestAdapter implements MuRequest {
	private final HttpRequest request;
	private final URI uri;
	private final HttpMethod method;
	private final Headers headers;

	public NettyRequestAdapter(ChannelHandlerContext ctx, HttpRequest request) {
		this.request = request;
		this.uri = URI.create(request.uri());
		this.method = HttpMethod.fromNetty(request.method());
		this.headers = new Headers(request.headers());
	}

	@Override
	public HttpMethod method() {
		return method;
	}

	@Override
	public URI uri() {
		return uri;
	}

	@Override
	public URI serverURI() {
		return uri;
	}

	@Override
	public Headers headers() {
		return headers;
	}

	@Override
	public String toString() {
		return method().name() + " " + uri();
	}
}
