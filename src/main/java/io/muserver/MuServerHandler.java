package io.muserver;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.*;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static io.netty.buffer.Unpooled.copiedBuffer;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static java.nio.charset.StandardCharsets.UTF_8;

class MuServerHandler extends SimpleChannelInboundHandler<Object> {
    private static final Logger log = LoggerFactory.getLogger(MuServerHandler.class);
    static final AttributeKey<String> PROTO_ATTRIBUTE = AttributeKey.newInstance("proto");
    private static final AttributeKey<State> STATE_ATTRIBUTE = AttributeKey.newInstance("state");

    private final List<AsyncMuHandler> handlers;
    private final MuStatsImpl stats;
    private final AtomicReference<MuServer> serverRef;

    public MuServerHandler(List<AsyncMuHandler> handlers, MuStatsImpl stats, AtomicReference<MuServer> serverRef) {
        this.handlers = handlers;
        this.stats = stats;
        this.serverRef = serverRef;
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
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        State state = ctx.channel().attr(STATE_ATTRIBUTE).get();
        if (state != null) {
            state.asyncContext.onDisconnected();
        }
        super.channelInactive(ctx);
    }

    protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
        try {
            onChannelRead(ctx, msg);
        } catch (Exception e) {
            log.info("Unhandled internal error", e);
            ctx.channel().close();
        }
    }

    private void onChannelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof HttpRequest) {
            HttpRequest request = (HttpRequest) msg;

            if (request.decoderResult().isFailure()) {
                stats.onInvalidRequest();
                handleHttpRequestDecodeFailure(ctx, request.decoderResult().cause());
            } else {

                if (HttpUtil.is100ContinueExpected(request)) {
                    int contentBody = request.headers().getInt("Content-Length", -1);
                    if (contentBody < Integer.MAX_VALUE) {
                        // TODO reject if body size too large
                        ctx.writeAndFlush(new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.CONTINUE));
                    } else {
                        sendSimpleResponse(ctx, "417 Expectation Failed", HttpResponseStatus.EXPECTATION_FAILED.code(), true);
                        return;
                    }
                }

                boolean handled = false;

                Method method;
                try {
                    method = Method.fromNetty(request.method());
                } catch (IllegalArgumentException e) {
                    sendSimpleResponse(ctx, "405 Method Not Allowed", 405, true);
                    return;
                }
                NettyRequestAdapter muRequest = new NettyRequestAdapter(ctx.channel(), request, serverRef, method);
                stats.onRequestStarted(muRequest);

                AsyncContext asyncContext = new AsyncContext(muRequest, new NettyResponseAdaptor(ctx, muRequest), stats);

                for (AsyncMuHandler handler : handlers) {
                    handled = handler.onHeaders(asyncContext, asyncContext.request.headers());
                    if (handled) {
                        ctx.channel().attr(STATE_ATTRIBUTE).set(new State(asyncContext, handler));
                        break;
                    }
                }
                if (!handled) {
                    send404(asyncContext);
                    asyncContext.complete(false);
                }
            }

        } else if (msg instanceof HttpContent) {
            HttpContent content = (HttpContent) msg;
            State state = ctx.channel().attr(STATE_ATTRIBUTE).get();
            if (state == null) {
                log.info("Got a chunk of message for an unknown request. This can happen when a request is rejected based on headers, and then the rejected body arrives.");
            } else {
                ByteBuf byteBuf = content.content();
                if (byteBuf.capacity() > 0) {
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
        MuResponse resp = asyncContext.response;
        resp.status(statusCode);
        resp.contentType(ContentTypes.TEXT_PLAIN);
        resp.write(message);
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
        sendSimpleResponse(ctx, message, code, true);
    }

    private static ChannelFuture sendSimpleResponse(ChannelHandlerContext ctx, String message, int code, boolean disconnect) {
        FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.valueOf(code), copiedBuffer(message.getBytes(UTF_8)));
        response.headers().set(HeaderNames.CONTENT_TYPE, ContentTypes.TEXT_PLAIN);
        response.headers().set(HeaderNames.CONTENT_LENGTH, message.length());
        if (disconnect) {
            response.headers().set(HeaderNames.CONNECTION, HeaderValues.CLOSE);
        }
        ChannelFuture future = ctx.writeAndFlush(response);
        if (disconnect) {
            future = future.addListener(ChannelFutureListener.CLOSE);
        }
        return future;
    }

}
