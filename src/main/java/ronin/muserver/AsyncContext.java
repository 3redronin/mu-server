package ronin.muserver;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;

public class AsyncContext {
	final ChannelHandlerContext ctx;
	public final MuRequest request;
	public final MuResponse response;

	public AsyncContext(ChannelHandlerContext ctx, MuRequest request, MuResponse response) {
		this.ctx = ctx;
		this.request = request;
		this.response = response;
	}

	public void complete() {
		ctx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
	}
}
