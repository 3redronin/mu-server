package io.muserver;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLSession;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Collections;
import java.util.Set;

import static io.netty.buffer.Unpooled.copiedBuffer;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static java.nio.charset.StandardCharsets.UTF_8;

class Http1Connection extends SimpleChannelInboundHandler<Object> implements HttpConnection, ConnectionState {
    private static final Logger log = LoggerFactory.getLogger(Http1Connection.class);

    private final NettyHandlerAdapter nettyHandlerAdapter;
    private final MuStatsImpl serverStats;
    private final MuStatsImpl connectionStats = new MuStatsImpl(null);
    private final MuServerImpl server;
    private final String proto;
    private final Instant startTime = Instant.now();
    private ChannelHandlerContext nettyCtx;
    private InetSocketAddress remoteAddress;
    private ConnectionState.Listener connectionStateListener;
    private Exchange currentExchange = null;

    Http1Connection(NettyHandlerAdapter nettyHandlerAdapter, MuServerImpl server, String proto) {
        this.nettyHandlerAdapter = nettyHandlerAdapter;
        this.serverStats = server.stats;
        this.server = server;
        this.proto = proto;
    }

    static SSLSession getSslSession(ChannelHandlerContext ctx) {
        SslHandler ssl = (SslHandler) ctx.channel().pipeline().get("ssl");
        return ssl.engine().getSession();
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        this.nettyCtx = ctx;
        remoteAddress = (InetSocketAddress) ctx.channel().remoteAddress();
        serverStats.onConnectionOpened();
        connectionStats.onConnectionOpened();
        super.handlerAdded(ctx);
        server.onConnectionStarted(this);
        ctx.channel().read();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        serverStats.onConnectionClosed();
        server.onConnectionEnded(this);
        if (currentExchange != null) {
            currentExchange.onConnectionEnded(ctx);
        }
        super.channelInactive(ctx);
    }

    protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
        try {
            onChannelRead(ctx, msg);
        } catch (Exception e) {
            log.info("Unhandled internal error. Closing connection.", e);
            ctx.channel().close();
        }
    }

    private void onChannelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof HttpRequest) {
            try {
                this.currentExchange = HttpExchange.create(server, proto, ctx, this, (HttpRequest) msg,
                    nettyHandlerAdapter, connectionStats, (exchange, newState) -> {
                        if (newState.endState()) {
                            nettyHandlerAdapter.onResponseComplete(exchange, serverStats, connectionStats);
                            ctx.channel().eventLoop().execute(() -> {
                                if (exchange.state() != HttpExchangeState.UPGRADED) {
                                    if (currentExchange != exchange) {
                                        throw new IllegalStateException("Expected current exchange to be " + exchange + " but was " + currentExchange);
                                    }
                                    currentExchange = null;
                                    exchange.request.cleanup();
                                    if (exchange.request.requestState() == RequestState.ERROR) {
                                        ctx.channel().close();
                                    } else {
                                        ctx.channel().read();
                                    }
                                }
                            });
                        }
                    });
            } catch (InvalidHttpRequestException ihr) {
                if (ihr.code == 429) {
                    connectionStats.onRejectedDueToOverload();
                    serverStats.onRejectedDueToOverload();
                } else {
                    connectionStats.onInvalidRequest();
                    serverStats.onInvalidRequest();
                }
                sendSimpleResponse(ctx, ihr.getMessage(), ihr.code);
                ctx.channel().read();
            }
        } else if (currentExchange != null) {
            currentExchange.onMessage(ctx, msg);
        } else {
            log.debug("Got a chunk of message for an unknown request. This can happen when a request is rejected based on headers, and then the rejected body arrives.");
            ctx.channel().read();
        }
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        if (connectionStateListener != null) {
            if (ctx.channel().isWritable()) {
                connectionStateListener.onWriteable();
            } else {
                connectionStateListener.onUnWriteable();
            }
        }
        super.channelWritabilityChanged(ctx);
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
        Exchange exchange = this.currentExchange;
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent ise = (IdleStateEvent) evt;
            if (exchange != null) {
                exchange.onIdleTimeout(ctx, ise);
            } else if (ise.state() == IdleState.ALL_IDLE) {
                ctx.channel().close();
                // Can't send a 408 so just closing context. See: https://stackoverflow.com/q/56722103/131578
                log.info("Closed idle connection to " + remoteAddress);
            }
        } else if (evt instanceof ExchangeUpgradeEvent) {
            ExchangeUpgradeEvent eue = (ExchangeUpgradeEvent) evt;
            if (eue.success()) {
                if (this.currentExchange instanceof HttpExchange) {
                    ((HttpExchange) this.currentExchange).addChangeListener((upgradeExchange, newState) -> {
                        if (newState.endState()) {
                            this.currentExchange = eue.newExchange;
                            ctx.channel().read();
                        }
                    });
                } else {
                    this.currentExchange = eue.newExchange;
                    ctx.channel().read();
                }
            } else {
                ctx.channel().close();
            }
        }
        super.userEventTriggered(ctx, evt);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        Exchange exchange = this.currentExchange;
        if (exchange != null) {
            exchange.onException(ctx, cause);
        } else {
            ctx.channel().close();
        }
    }

    @Override
    public String protocol() {
        return "HTTP/1.1";
    }

    @Override
    public boolean isHttps() {
        return "https".equals(proto);
    }

    @Override
    public String httpsProtocol() {
        return isHttps() ? getSslSession(nettyCtx).getProtocol() : null;
    }

    @Override
    public String cipher() {
        return isHttps() ? getSslSession(nettyCtx).getCipherSuite() : null;
    }

    @Override
    public Instant startTime() {
        return startTime;
    }

    @Override
    public InetSocketAddress remoteAddress() {
        return remoteAddress;
    }

    @Override
    public long completedRequests() {
        return connectionStats.completedRequests();
    }

    @Override
    public long invalidHttpRequests() {
        return connectionStats.invalidHttpRequests();
    }

    @Override
    public long rejectedDueToOverload() {
        return connectionStats.rejectedDueToOverload();
    }

    @Override
    public Set<MuRequest> activeRequests() {
        Exchange currentExchange = this.currentExchange;
        return currentExchange instanceof HttpExchange
            ? Collections.singleton(((HttpExchange) currentExchange).request)
            : Collections.emptySet();
    }

    @Override
    public Set<MuWebSocket> activeWebsockets() {
        Exchange currentExchange = this.currentExchange;
        return currentExchange instanceof MuWebSocketSessionImpl
            ? Collections.singleton(((MuWebSocketSessionImpl) currentExchange).muWebSocket)
            : Collections.emptySet();
    }

    @Override
    public MuServer server() {
        return server;
    }

    @Override
    public void registerConnectionStateListener(ConnectionState.Listener listener) {
        this.connectionStateListener = listener;
    }
}
