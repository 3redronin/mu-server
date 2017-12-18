package ronin.muserver;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

class MuServerHandler extends SimpleChannelInboundHandler<Object> {
	private final List<AsyncMuHandler> handlers;
	private final ConcurrentHashMap<ChannelHandlerContext, State> state = new ConcurrentHashMap<>();

	public MuServerHandler(List<AsyncMuHandler> handlers) {
		this.handlers = handlers;
	}

	private static final class State {
		public final AsyncContext asyncContext;
		public final AsyncMuHandler handler;
		private State(AsyncContext asyncContext, AsyncMuHandler handler) {
			this.asyncContext = asyncContext;
			this.handler = handler;
		}
	}

	@Override
	protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
		if (msg instanceof HttpRequest) {
			HttpRequest request = (HttpRequest) msg;
			HttpResponse response = new DefaultHttpResponse(HTTP_1_1, HttpResponseStatus.OK);

			boolean handled = false;

			AsyncContext asyncContext = new AsyncContext(new NettyRequestAdapter(ctx, request), new NettyResponseAdaptor(ctx, response));

			for (AsyncMuHandler handler : handlers) {
				handled = handler.onHeaders(asyncContext, asyncContext.request.headers());
				if (handled) {
					state.put(ctx, new State(asyncContext, handler));
					break;
				}
			}
			if (!handled) {
				System.out.println("No handler found");
				asyncContext.response.status(404);
				asyncContext.complete();
			}

		} else if (msg instanceof HttpContent) {
			HttpContent content = (HttpContent) msg;
			State state = this.state.get(ctx);
			if (state == null) {
				// ummmmmm
				System.out.println("Got a chunk of message for an unknown request");
			} else {
				ByteBuf byteBuf = content.content();
				if (byteBuf.capacity() > 0) {
					// TODO: why does the buffer need to be copied?
					ByteBuffer byteBuffer = byteBuf.copy().nioBuffer();
					state.handler.onRequestData(state.asyncContext, byteBuffer);
				}
				if (msg instanceof LastHttpContent) {
					state.handler.onRequestComplete(state.asyncContext);
				}
			}
		}
	}
}
