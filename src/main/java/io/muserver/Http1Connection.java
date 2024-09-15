package io.muserver;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import kotlin.NotImplementedError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.cert.Certificate;
import java.time.Instant;
import java.util.Collections;
import java.util.Date;
import java.util.Optional;
import java.util.Set;

import static io.netty.buffer.Unpooled.copiedBuffer;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static java.nio.charset.StandardCharsets.UTF_8;

class Http1Connection extends SimpleChannelInboundHandler<Object> implements HttpConnection {
    private static final Logger log = LoggerFactory.getLogger(Http1Connection.class);

    private final NettyHandlerAdapter nettyHandlerAdapter;
    private final MuStatsImpl serverStats;
    private final MuStatsImpl connectionStats = new MuStatsImpl(null);
    private final MuServerImpl server;
    private final String proto;
    private final Instant startTime = Instant.now();
    private ChannelHandlerContext nettyCtx;
    private InetSocketAddress remoteAddress;
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
            log.warn("Unhandled internal error. Closing connection.", e);
            ctx.channel().close();
        }
    }

    private void onChannelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof HttpRequest) {
            try {
                this.currentExchange = HttpExchange.create(server, proto, ctx, this, (HttpRequest) msg,
                    nettyHandlerAdapter, connectionStats,
                    (exchange, newState) -> {
                        if (newState == RequestState.RECEIVING_BODY) {
                            ctx.channel().read();
                        }
                    },
                    (exchange, newState) -> {
                        if (newState.endState()) {
                            nettyHandlerAdapter.onResponseComplete(exchange, serverStats, connectionStats);
                            ctx.channel().eventLoop().execute(() -> {
                                if (exchange.state() != HttpExchangeState.UPGRADED) {
                                    if (this.currentExchange != exchange) {
                                        throw new IllegalStateException("Expected current exchange to be " + exchange + " but was " + this.currentExchange);
                                    }
                                    this.currentExchange = null;
                                    exchange.request.cleanup();
                                    if (exchange.state() == HttpExchangeState.ERRORED) {
                                        ctx.channel().close();
                                    } else {
                                        ctx.channel().read(); // should it actually read after request complete?
                                    }
                                }
                            });
                        }
                    });

            } catch (InvalidHttpRequestException ihr) {
                if (ihr.code == 429 || ihr.code == 503) {
                    connectionStats.onRejectedDueToOverload();
                    serverStats.onRejectedDueToOverload();
                } else {
                    connectionStats.onInvalidRequest();
                    serverStats.onInvalidRequest();
                }
                sendSimpleResponse(ctx, ihr.getMessage(), ihr.code);
                ctx.channel().read();
            } catch (RedirectException e) {
                sendRedirect(ctx, e.location);
            }
        } else if (currentExchange != null) {
            currentExchange.onMessage(ctx, msg, error -> {
                if (error == null) {
                    if (!(msg instanceof LastHttpContent)) {
                        ctx.channel().read();
                    }
                } else {
                    ctx.fireUserEventTriggered(new MuExceptionFiredEvent(currentExchange, -1, error));
                }
            });
        } else {
            log.debug("Got a chunk of message for an unknown request. This can happen when a request is rejected based on headers, and then the rejected body arrives.");
            ctx.channel().read();
        }
    }

    private static ChannelFuture sendSimpleResponse(ChannelHandlerContext ctx, String message, int code) {
        byte[] bytes = message.getBytes(UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.valueOf(code), copiedBuffer(bytes));
        response.headers().set(HeaderNames.DATE, Mutils.toHttpDate(new Date()));
        response.headers().set(HeaderNames.CONTENT_TYPE, ContentTypes.TEXT_PLAIN_UTF8);
        response.headers().set(HeaderNames.CONTENT_LENGTH, bytes.length);
        return ctx.writeAndFlush(response);
    }
    private static ChannelFuture sendRedirect(ChannelHandlerContext ctx, URI location) {
        FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.valueOf(302));
        response.headers().set(HeaderNames.DATE, Mutils.toHttpDate(new Date()));
        response.headers().set(HeaderNames.LOCATION, location.toString());
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
                    HttpExchange httpExchange = (HttpExchange) this.currentExchange;
                    httpExchange.addChangeListener((upgradeExchange, newState) -> {
                        if (newState == HttpExchangeState.UPGRADED) {
                            this.currentExchange = eue.newExchange;
                            this.currentExchange.onUpgradeComplete(ctx);
                            ctx.channel().read();
                        } else if (newState == HttpExchangeState.ERRORED) {
                            eue.newExchange.onConnectionEnded(ctx);
                        }
                    });
                    httpExchange.response.setWebsocket();
                } else {
                    this.currentExchange = eue.newExchange;
                }
                ctx.channel().read();
            } else {
                ctx.channel().close();
            }
        } else if (evt instanceof MuExceptionFiredEvent) {
            MuExceptionFiredEvent mefe = (MuExceptionFiredEvent) evt;
            exceptionCaught(ctx, mefe.error);
        }
        super.userEventTriggered(ctx, evt);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        Exchange exchange = this.currentExchange;
        if (exchange != null) {
            if (exchange.onException(ctx, cause)) {
                ctx.channel().close();
            }
        } else {
            ctx.channel().close();
        }
    }

    @Override
    public String protocol() {
        return "HTTP/1.1";
    }

    @Override
    public HttpVersion httpVersion() {
        throw new NotImplementedError();
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
    public Optional<Certificate> clientCertificate() {
        return fromContext(nettyCtx);
    }

    static Optional<Certificate> fromContext(ChannelHandlerContext channelHandlerContext) {
        try {
            SslHandler sslhandler = (SslHandler) channelHandlerContext.channel().pipeline().get("ssl");
            if (sslhandler == null) {
                return Optional.empty();
            }
            SSLSession session = sslhandler.engine().getSession();
            return Optional.of(session.getPeerCertificates()[0]);
        } catch (SSLPeerUnverifiedException e) {
            return Optional.empty();
        }
    }

}
