package ronin.muserver;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;

import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

class MuServerHandler extends SimpleChannelInboundHandler<Object> {
	private final MuHandler[] handlers;
	private final ConcurrentHashMap<ChannelHandlerContext, MuAsyncHandler> state = new ConcurrentHashMap<ChannelHandlerContext, MuAsyncHandler>();

	public MuServerHandler(MuHandler[] handlers) {
		this.handlers = handlers;
	}

	@Override
	protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
		if (msg instanceof HttpRequest) {
			System.out.println("Got request");
			HttpRequest request = (HttpRequest) msg;


			HttpResponse response = new DefaultHttpResponse(HTTP_1_1, HttpResponseStatus.OK);

			for (MuHandler handler : handlers) {
				AsyncContext asyncContext = new AsyncContext(ctx, new NettyRequestAdapter(ctx, request), new NettyResponseAdaptor(ctx, response));
				MuAsyncHandler asyncHandler = handler.start(asyncContext);
				if (asyncHandler != null) {
					state.put(ctx, asyncHandler);
					asyncHandler.onHeaders();
					break;
				}
			}

			ctx.write(response);
		} else if (msg instanceof HttpContent) {
			HttpContent content = (HttpContent) msg;
			MuAsyncHandler asyncHandler = state.get(ctx);
			if (asyncHandler == null) {
				// ummmmmm
			} else {
				ByteBuf byteBuf = content.content();
				if (byteBuf.capacity() > 0) {
					ByteBuffer byteBuffer = byteBuf.nioBuffer();
					asyncHandler.onRequestData(byteBuffer);
				}
				if (msg instanceof LastHttpContent) {
					asyncHandler.onRequestComplete();
				}
			}
		}
	}
}
