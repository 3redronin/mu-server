package ronin.muserver;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;

import java.net.URI;

public class NettyRequestAdapter implements MuRequest {
	private final HttpRequest request;

	public NettyRequestAdapter(ChannelHandlerContext ctx, HttpRequest request) {
		this.request = request;
	}

	@Override
	public HttpMethod method() {
		return null;
	}

	@Override
	public URI uri() {
		return null;
	}

	@Override
	public URI serverURI() {
		return null;
	}
}
