package io.muserver;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.*;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static io.netty.buffer.Unpooled.copiedBuffer;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static java.nio.charset.StandardCharsets.UTF_8;

class MuServerHandler extends SimpleChannelInboundHandler<Object> {
    static final AttributeKey<String> PROTO_ATTRIBUTE = AttributeKey.newInstance("proto");

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

	protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
		if (msg instanceof HttpRequest) {
			HttpRequest request = (HttpRequest) msg;

			if (request.decoderResult().isFailure()) {
				handleHttpRequestDecodeFailure(ctx, request.decoderResult().cause());
			} else {

				HttpResponse response = new DefaultHttpResponse(HTTP_1_1, HttpResponseStatus.OK, false);

				boolean handled = false;
                Attribute<String> proto = ctx.channel().attr(PROTO_ATTRIBUTE);

                NettyRequestAdapter muRequest = new NettyRequestAdapter(proto.get(), request);
                AsyncContext asyncContext = new AsyncContext(muRequest, new NettyResponseAdaptor(ctx, muRequest, response));

				for (AsyncMuHandler handler : handlers) {
					handled = handler.onHeaders(asyncContext, asyncContext.request.headers());
					if (handled) {
						state.put(ctx, new State(asyncContext, handler));
						break;
					}
				}
				if (!handled) {
                    send404(asyncContext);
					asyncContext.complete();
				}
			}

		} else if (msg instanceof HttpContent) {
			HttpContent content = (HttpContent) msg;
			State state = this.state.get(ctx);
			if (state == null) {
				// This can happen when a request is rejected based on headers, and then the rejected body arrives
				System.out.println("Got a chunk of message for an unknown request");
			} else {
				ByteBuf byteBuf = content.content();
				if (byteBuf.capacity() > 0) {
					// TODO: why does the buffer need to be copied? Does this only need to happen for sync processing?
                    ByteBuf copy = byteBuf.copy();
                    ByteBuffer byteBuffer = ByteBuffer.allocate(byteBuf.capacity());
                    copy.readBytes(byteBuffer).release();
                    byteBuffer.flip();
					state.handler.onRequestData(state.asyncContext, byteBuffer);
				}
				if (msg instanceof LastHttpContent) {
					state.handler.onRequestComplete(state.asyncContext);
				}
			}
		}
	}

    public static void send404(AsyncContext asyncContext) {
        sendPlainText(asyncContext, "404 Not Found", 404);
    }

    public static void sendPlainText(AsyncContext asyncContext, String message, int statusCode) {
        asyncContext.response.status(statusCode);
        asyncContext.response.contentType(ContentTypes.TEXT_PLAIN);
        asyncContext.response.headers().set(HeaderNames.CONTENT_LENGTH, message.length());
        asyncContext.response.write(message);
    }

    private void handleHttpRequestDecodeFailure(ChannelHandlerContext ctx, Throwable cause) {
		String message = "Server error";
		int code = 500;
		if (cause instanceof TooLongFrameException) {
			if (cause.getMessage().contains("header is larger")) {
				code = 431;
				message = "HTTP headers too large";
			} else if (cause.getMessage().contains("line is larger")) {
				code = 414;
				message = "URI too long";
			}
		}
		FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.valueOf(code), copiedBuffer(message.getBytes(UTF_8)));
		response.headers().set(HeaderNames.CONTENT_TYPE, ContentTypes.TEXT_PLAIN);
		response.headers().set(HeaderNames.CONTENT_LENGTH, message.length());
		ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
	}

}
