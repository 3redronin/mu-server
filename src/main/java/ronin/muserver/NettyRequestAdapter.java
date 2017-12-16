package ronin.muserver;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;

import java.net.URI;

public class NettyRequestAdapter implements MuRequest {
	private final HttpRequest request;
    private final URI uri;

    public NettyRequestAdapter(ChannelHandlerContext ctx, HttpRequest request) {
		this.request = request;
		this.uri = URI.create(request.uri());
	}

	@Override
	public HttpMethod method() {
		return HttpMethod.fromNetty(request.method());
	}

	@Override
	public URI uri() {
		return uri;
	}

	@Override
	public URI serverURI() {
		return null;
	}

    @Override
    public String toString() {
        return method().name() + " " + uri();
    }
}
