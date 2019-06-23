package io.muserver;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static io.netty.buffer.Unpooled.copiedBuffer;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static java.nio.charset.StandardCharsets.UTF_8;

class Http1Connection extends SimpleChannelInboundHandler<Object> {
    private static final Logger log = LoggerFactory.getLogger(Http1Connection.class);
    private static final AttributeKey<AsyncContext> STATE_ATTRIBUTE = AttributeKey.newInstance("state"); // todo, just store as a volatile field?
    static final AttributeKey<MuWebSocketSessionImpl> WEBSOCKET_ATTRIBUTE = AttributeKey.newInstance("ws"); // todo, just store as a volatile field?

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

    static void setAsyncContext(ChannelHandlerContext ctx, AsyncContext value) {
        ctx.channel().attr(STATE_ATTRIBUTE).set(value);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        ctx.channel().config().setAutoRead(false);
        ctx.read();
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        AsyncContext asyncContext = getAsyncContext(ctx);
        if (asyncContext != null) {
            asyncContext.onCancelled(true);
        }
        MuWebSocketSessionImpl webSocket = getWebSocket(ctx);
        if (webSocket != null) {
            webSocket.muWebSocket.onError(new ClientDisconnectedException());
        }
        super.channelInactive(ctx);
    }

    protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
        try {
            if (onChannelRead(ctx, msg)) {
                ctx.channel().read();
            }
        } catch (Exception e) {
            log.info("Unhandled internal error", e);
            ctx.channel().close();
        }
    }

    private boolean onChannelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        boolean readyToRead = true;
        if (msg instanceof HttpRequest) {
            HttpRequest request = (HttpRequest) msg;

            if (request.decoderResult().isFailure()) {
                stats.onInvalidRequest();
                handleHttpRequestDecodeFailure(ctx, request.decoderResult().cause());
                return false;
            } else {

                if (HttpUtil.is100ContinueExpected(request)) {
                    int contentBody = request.headers().getInt("Content-Length", -1);
                    if (contentBody < Integer.MAX_VALUE) {
                        // TODO reject if body size too large
                        ctx.writeAndFlush(new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.CONTINUE));
                    } else {
                        sendSimpleResponse(ctx, "417 Expectation Failed", HttpResponseStatus.EXPECTATION_FAILED.code());
                        return true;
                    }
                }

                if (!request.headers().contains(HttpHeaderNames.HOST)) {
                    stats.onInvalidRequest();
                    sendSimpleResponse(ctx, "400 Bad Request", 400);
                    return true;
                }

                Method method;
                try {
                    method = Method.fromNetty(request.method());
                } catch (IllegalArgumentException e) {
                    stats.onInvalidRequest();
                    sendSimpleResponse(ctx, "405 Method Not Allowed", 405);
                    return true;
                }
                final Http1Headers headers = new Http1Headers(request.headers());

                String relativeUri;
                try {
                    relativeUri = getRelativeUri(request);
                } catch (Exception e) {
                    stats.onInvalidRequest();
                    sendSimpleResponse(ctx, "400 Bad Request", 400);
                    return true;
                }

                NettyRequestAdapter muRequest = new NettyRequestAdapter(ctx, ctx.channel(), request, headers, serverRef, method,
                    proto, relativeUri, HttpUtil.isKeepAlive(request), headers.get(HeaderNames.HOST), request.protocolVersion().text());
                stats.onRequestStarted(muRequest);

                Http1Response muResponse = new Http1Response(ctx, muRequest, new Http1Headers());

                AsyncContext asyncContext = new AsyncContext(muRequest, muResponse, stats);
                setAsyncContext(ctx, asyncContext);
                readyToRead = false;
                DoneCallback addedToExecutorCallback = error -> {
                    ctx.channel().read();
                    if (error != null) {
                        stats.onRejectedDueToOverload();
                        try {
                            sendSimpleResponse(ctx, "503 Service Unavailable", 503);
                        } catch (Exception e) {
                            ctx.close();
                        } finally {
                            stats.onRequestEnded(muRequest);
                        }
                    }
                };
                nettyHandlerAdapter.onHeaders(addedToExecutorCallback, asyncContext, asyncContext.request.headers());
            }

        } else if (msg instanceof HttpContent) {
            HttpContent content = (HttpContent) msg;
            AsyncContext asyncContext = getAsyncContext(ctx);
            if (asyncContext == null) {
                log.debug("Got a chunk of message for an unknown request. This can happen when a request is rejected based on headers, and then the rejected body arrives.");
            } else {
                ByteBuf byteBuf = content.content();
                NettyHandlerAdapter.passDataToHandler(byteBuf, nettyHandlerAdapter, asyncContext);
                if (msg instanceof LastHttpContent) {
                    nettyHandlerAdapter.onRequestComplete(asyncContext);
                }
            }
        } else if (msg instanceof WebSocketFrame) {
            MuWebSocketSessionImpl session = getWebSocket(ctx);
            if (session != null) {
                readyToRead = false;
                session.connectedPromise.addListener(future -> {
                    MuWebSocket muWebSocket = session.muWebSocket;
                    DoneCallback onComplete = error -> {
                        if (error == null) {
                            ctx.channel().read();
                        } else {
                            handleWebsockError(ctx, muWebSocket, error);
                        }
                    };
                    try {
                        if (msg instanceof TextWebSocketFrame) {
                            muWebSocket.onText(((TextWebSocketFrame) msg).text(), onComplete);
                        } else if (msg instanceof BinaryWebSocketFrame) {
                            ByteBuf content = ((ByteBufHolder) msg).content();
                            content.retain();
                            muWebSocket.onBinary(content.nioBuffer(), error -> {
                                content.release();
                                onComplete.onComplete(error);
                            });
                        } else if (msg instanceof PingWebSocketFrame) {
                            ByteBuf content = ((ByteBufHolder) msg).content();
                            content.retain();
                            muWebSocket.onPing(content.nioBuffer(), error -> {
                                content.release();
                                onComplete.onComplete(error);
                            });
                        } else if (msg instanceof PongWebSocketFrame) {
                            ByteBuf content = ((ByteBufHolder) msg).content();
                            content.retain();
                            muWebSocket.onPong(content.nioBuffer(), error -> {
                                content.release();
                                onComplete.onComplete(error);
                            });
                        } else if (msg instanceof CloseWebSocketFrame) {
                            CloseWebSocketFrame cwsf = (CloseWebSocketFrame) msg;
                            muWebSocket.onClientClosed(cwsf.statusCode(), cwsf.reasonText());
                            clearWebSocket(ctx);
                            onComplete.onComplete(null);
                        }
                    } catch (Throwable e) {
                        handleWebsockError(ctx, muWebSocket, e);
                    }
                });
            }
        }
        return readyToRead;
    }

    private void handleWebsockError(ChannelHandlerContext ctx, MuWebSocket muWebSocket, Throwable e) {
        try {
            clearWebSocket(ctx);
            muWebSocket.onError(e);
        } catch (Exception ex) {
            log.warn("Exception thrown by " + muWebSocket.getClass() + "#onError so will close connection", ex);
            ctx.close();
        }
    }

    static void clearWebSocket(ChannelHandlerContext ctx) {
        ctx.channel().attr(WEBSOCKET_ATTRIBUTE).set(null);
    }

    private static String getRelativeUri(HttpRequest request) throws URISyntaxException {
        URI requestUri = new URI(request.uri()).normalize();
        String s = requestUri.getRawPath();
        if (Mutils.nullOrEmpty(s)) {
            s = "/";
        }
        String q = requestUri.getRawQuery();
        if (q != null) {
            s += "?" + q;
        }
        return s;
    }

    private void handleHttpRequestDecodeFailure(ChannelHandlerContext ctx, Throwable cause) {
        String message = "Server error";
        int code = 500;
        if (cause instanceof TooLongFrameException) {
            if (cause.getMessage().contains("header is larger")) {
                code = 431;
                message = "431 Request Header Fields Too Large";
            } else if (cause.getMessage().contains("line is larger")) {
                code = 414;
                message = "414 Request-URI Too Long";
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
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent ise = (IdleStateEvent) evt;
            MuWebSocketSessionImpl session = getWebSocket(ctx);
            if (session != null) {
                if (ise.state() == IdleState.READER_IDLE) {
                    try {
                        session.muWebSocket.onError(new TimeoutException("No messages received on websocket"));
                    } catch (Exception e) {
                        log.warn("Error while processing idle timeout", e);
                        ctx.close();
                    }
                } else if (ise.state() == IdleState.WRITER_IDLE) {
                    session.sendPing(ByteBuffer.wrap(MuWebSocketSessionImpl.PING_BYTES), DoneCallback.NoOp);
                }
            } else {
                AsyncContext asyncContext = getAsyncContext(ctx);
                boolean activeRequest = asyncContext != null && !asyncContext.isComplete();
                if (activeRequest) {
                    if (!asyncContext.response.hasStartedSendingData()) {
                        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.REQUEST_TIMEOUT);
                        resp.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
                        ctx.writeAndFlush(resp);
                    }
                    asyncContext.onCancelled(true);
                } else {
                    // Can't send a 408 so just closing context. See: https://stackoverflow.com/q/56722103/131578
                    ctx.channel().close();
//                    DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.REQUEST_TIMEOUT);
//                    resp.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
//                    ctx.writeAndFlush(resp)
//                        .addListener(ChannelFutureListener.CLOSE);
                }
            }
        }
        super.userEventTriggered(ctx, evt);
    }

    private MuWebSocketSessionImpl getWebSocket(ChannelHandlerContext ctx) {
        return ctx.channel().attr(WEBSOCKET_ATTRIBUTE).get();
    }

    static AsyncContext getAsyncContext(ChannelHandlerContext ctx) {
        return ctx.channel().attr(STATE_ATTRIBUTE).get();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        AsyncContext asyncContext = getAsyncContext(ctx);
        if (asyncContext != null) {
            if (log.isDebugEnabled()) {
                log.debug(cause.getClass().getName() + " (" + cause.getMessage() + ") for " + ctx +
                    " so will disconnect this client");
            }
            asyncContext.onCancelled(true);
        } else if (cause instanceof CorruptedFrameException) {
            MuWebSocketSessionImpl webSocket = getWebSocket(ctx);
            if (webSocket != null) {
                try {
                    webSocket.muWebSocket.onError(new WebSocketProtocolException(cause.getMessage(), cause));
                } catch (Exception e) {
                    ctx.close();
                }
                return;
            }
        } else {
            log.debug("Exception for unknown ctx " + ctx, cause);
        }
        ctx.close();
    }
}
