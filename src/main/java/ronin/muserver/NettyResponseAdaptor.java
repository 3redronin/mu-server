package ronin.muserver;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.CharsetUtil;

class NettyResponseAdaptor implements MuResponse {
	private final ChannelHandlerContext ctx;
	private final HttpResponse response;

	public NettyResponseAdaptor(ChannelHandlerContext ctx, HttpResponse response) {
		this.ctx = ctx;
		this.response = response;
	}

	@Override
	public int status() {
		return response.status().code();
	}

	@Override
	public void status(int value) {
		response.setStatus(HttpResponseStatus.valueOf(value));
	}

	@Override
	public void write(String text) {
		HttpContent msg = new DefaultHttpContent(Unpooled.copiedBuffer(text, CharsetUtil.UTF_8));
		ctx.write(msg);
	}
}
