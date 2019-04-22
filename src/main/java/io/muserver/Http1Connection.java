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

import java.util.concurrent.atomic.AtomicReference;

import static io.netty.buffer.Unpooled.copiedBuffer;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static java.nio.charset.StandardCharsets.UTF_8;

class Http1Connection extends SimpleChannelInboundHandler<Object> {
    private static final Logger log = LoggerFactory.getLogger(Http1Connection.class);
    static final AttributeKey<State> STATE_ATTRIBUTE = AttributeKey.newInstance("state");

    private final NettyHandlerAdapter nettyHandlerAdapter;
    private final MuStatsImpl stats;
    private final AtomicReference<MuServer> serverRef;
    private final String proto;

    Http1Connection(NettyHandlerAdapter nettyHandlerAdapter, MuStatsImpl stats, AtomicReference<MuServer> serverRef, String proto) {
        this.nettyHandlerAdapter = nettyHandlerAdapter;
        this.stats = stats;
        this.serverRef = serverRef;
        this.proto = proto;
    }

    static final class State {
        final AsyncContext asyncContext;
        final NettyHandlerAdapter handler;

        State(AsyncContext asyncContext, NettyHandlerAdapter handler) {
            this.asyncContext = asyncContext;
            this.handler = handler;
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        State state = ctx.channel().attr(STATE_ATTRIBUTE).get();
        if (state != null) {
            state.asyncContext.onCancelled(true);
        }
        super.channelInactive(ctx);
    }

    protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
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
                        sendSimpleResponse(ctx, "417 Expectation Failed", HttpResponseStatus.EXPECTATION_FAILED.code());
                        return;
                    }
                }

                if (!request.headers().contains(HttpHeaderNames.HOST)) {
                    sendSimpleResponse(ctx, "400 Bad Request", 400);
                    return;
                }

                Method method;
                try {
                    method = Method.fromNetty(request.method());
                } catch (IllegalArgumentException e) {
                    sendSimpleResponse(ctx, "405 Method Not Allowed", 405);
                    return;
                }
                final H1Headers headers = new H1Headers(request.headers());
                NettyRequestAdapter muRequest = new NettyRequestAdapter(ctx.channel(), request, headers, serverRef, method, proto, request.uri(), HttpUtil.isKeepAlive(request), headers.get(HeaderNames.HOST));
                stats.onRequestStarted(muRequest);


                AsyncContext asyncContext = new AsyncContext(muRequest, new Http1Response(ctx, muRequest, new H1Headers()), stats);
                ctx.channel().attr(STATE_ATTRIBUTE).set(new State(asyncContext, nettyHandlerAdapter));
                nettyHandlerAdapter.onHeaders(asyncContext, asyncContext.request.headers());
            }

        } else if (msg instanceof HttpContent) {
            HttpContent content = (HttpContent) msg;
            State state = ctx.channel().attr(STATE_ATTRIBUTE).get();
            if (state == null) {
                log.debug("Got a chunk of message for an unknown request. This can happen when a request is rejected based on headers, and then the rejected body arrives.");
            } else {
                ByteBuf byteBuf = content.content();
                NettyHandlerAdapter.passDataToHandler(byteBuf, state);
                if (msg instanceof LastHttpContent) {
                    state.handler.onRequestComplete(state.asyncContext);
                }
            }
        }
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
        sendSimpleResponse(ctx, message, code).addListener(ChannelFutureListener.CLOSE);
    }

    private static ChannelFuture sendSimpleResponse(ChannelHandlerContext ctx, String message, int code) {
        byte[] bytes = message.getBytes(UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.valueOf(code), copiedBuffer(bytes));
        response.headers().set(HeaderNames.CONTENT_TYPE, ContentTypes.TEXT_PLAIN_UTF8);
        response.headers().set(HeaderNames.CONTENT_LENGTH, bytes.length);
        return ctx.writeAndFlush(response);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        State state = ctx.channel().attr(STATE_ATTRIBUTE).get();
        if (state != null) {
            log.debug(cause.getClass().getName() + " (" + cause.getMessage() + ") for " + ctx + " so will disconnect this client");
            state.asyncContext.onCancelled(true);
        } else {
            log.debug("Exception for unknown ctx " + ctx, cause);
        }
    }
}
