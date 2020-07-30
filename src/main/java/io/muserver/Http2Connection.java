package io.muserver;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http2.*;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static io.netty.buffer.Unpooled.EMPTY_BUFFER;
import static io.netty.buffer.Unpooled.copiedBuffer;
import static java.nio.charset.StandardCharsets.UTF_8;

final class Http2Connection extends Http2ConnectionHandler implements Http2FrameListener, HttpConnection {
    private static final Logger log = LoggerFactory.getLogger(Http2Connection.class);

    private final MuServerImpl server;
    private final NettyHandlerAdapter nettyHandlerAdapter;
    private final ConcurrentHashMap<Integer, AsyncContext> contexts = new ConcurrentHashMap<>();
    private volatile int lastStreamId = 0;
    private final MuStatsImpl connectionStats = new MuStatsImpl(null);
    private InetSocketAddress remoteAddress;
    private final Instant startTime = Instant.now();
    private ChannelHandlerContext nettyContext;

    Http2Connection(Http2ConnectionDecoder decoder, Http2ConnectionEncoder encoder,
                    Http2Settings initialSettings, MuServerImpl server, NettyHandlerAdapter nettyHandlerAdapter) {
        super(decoder, encoder, initialSettings);
        this.server = server;
        this.nettyHandlerAdapter = nettyHandlerAdapter;
    }


    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        server.stats.onConnectionOpened();
        remoteAddress = (InetSocketAddress) ctx.channel().remoteAddress();
        this.nettyContext = ctx;
        super.handlerAdded(ctx);
        server.onConnectionStarted(this);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        server.stats.onConnectionClosed();
        server.onConnectionEnded(this);
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        closeAllAndDisconnect(ctx, Http2Error.INTERNAL_ERROR);
    }

    private void closeAllAndDisconnect(ChannelHandlerContext ctx, Http2Error error) {
        if (error != null) {
            encoder().writeGoAway(ctx, lastStreamId, error.code(), EMPTY_BUFFER, ctx.channel().newPromise());
        }
        for (AsyncContext asyncContext : contexts.values()) {
            asyncContext.onCancelled(true);
        }
        ctx.close();
    }

    private ChannelFuture sendSimpleResponse(ChannelHandlerContext ctx, int streamId, String message, int code) {
        byte[] bytes = message.getBytes(UTF_8);
        ByteBuf content = copiedBuffer(bytes);

        io.netty.handler.codec.http2.Http2Headers headers = new DefaultHttp2Headers();
        headers.status(String.valueOf(code));
        headers.set(HeaderNames.CONTENT_TYPE, ContentTypes.TEXT_PLAIN_UTF8);
        headers.set(HeaderNames.CONTENT_LENGTH, String.valueOf(bytes.length));
        encoder().writeHeaders(ctx, streamId, headers, 0, false, ctx.newPromise());
        return Http2Response.writeToChannel(ctx, encoder(), streamId, content, true);
    }

    @Override
    public int onDataRead(ChannelHandlerContext ctx, int streamId, ByteBuf data, int padding, boolean endOfStream) {
        int processed = data.readableBytes() + padding;

        AsyncContext asyncContext = contexts.get(streamId);
        if (asyncContext == null) {
            log.debug("Got a chunk of message for an unknown request. This can happen when a request is rejected based on headers, and then the rejected body arrives.");
        } else {
            NettyHandlerAdapter.passDataToHandler(data, asyncContext);
            if (endOfStream) {
                nettyHandlerAdapter.onRequestComplete(asyncContext);
                contexts.remove(streamId);
            }
        }
        return processed;
    }

    @Override
    public void onHeadersRead(ChannelHandlerContext ctx, int streamId,
                              io.netty.handler.codec.http2.Http2Headers headers, int padding, boolean endOfStream) {
        lastStreamId = streamId;

        HttpMethod nettyMeth = HttpMethod.valueOf(headers.method().toString().toUpperCase());
        Method muMethod;
        try {
            muMethod = Method.fromNetty(nettyMeth);
        } catch (IllegalArgumentException e) {
            server.stats.onInvalidRequest();
            connectionStats.onInvalidRequest();
            sendSimpleResponse(ctx, streamId, "405 Method Not Allowed", 405);
            return;
        }

        String uri = headers.path().toString();
        ServerSettings settings = server.settings();
        if (uri.length() > settings.maxUrlSize) {
            server.stats.onInvalidRequest();
            connectionStats.onInvalidRequest();
            sendSimpleResponse(ctx, streamId, "414 Request-URI Too Long", 414);
            return;
        }

        HttpRequest nettyReq = new Http2To1RequestAdapter(streamId, nettyMeth, uri, headers);
        boolean hasRequestBody = !endOfStream;
        if (hasRequestBody) {
            long bodyLen = headers.getLong(HeaderNames.CONTENT_LENGTH, -1L);
            if (bodyLen == 0) {
                hasRequestBody = false;
            } else if (bodyLen > settings.maxRequestSize) {
                server.stats.onInvalidRequest();
                connectionStats.onInvalidRequest();
                sendSimpleResponse(ctx, streamId, "413 Payload Too Large", 413);
                return;
            }
        }
        Http2Headers muHeaders = new Http2Headers(headers, hasRequestBody);
        String host = headers.authority().toString();
        muHeaders.set(HeaderNames.HOST, host);
        NettyRequestAdapter muReq = new NettyRequestAdapter(ctx, ctx.channel(), nettyReq, muHeaders, server, muMethod, "https", uri, true, host, "HTTP/2", this);

        if (settings.block(muReq)) {
            server.stats.onRejectedDueToOverload();
            connectionStats.onRejectedDueToOverload();
            sendSimpleResponse(ctx, streamId, "429 Too Many Requests", 429);
            return;
        }

        server.stats.onRequestStarted(muReq);
        connectionStats.onRequestStarted(muReq);
        Http2Response resp = new Http2Response(ctx, muReq, new Http2Headers(), encoder(), streamId, settings);

        AsyncContext asyncContext = new AsyncContext(muReq, resp, (info) -> {
            nettyHandlerAdapter.onResponseComplete(info, server.stats, connectionStats);
            contexts.remove(streamId);
        });

        contexts.put(streamId, asyncContext);
        DoneCallback addedToExecutorCallback = error -> {
            ctx.channel().read();
            if (error != null) {
                server.stats.onRejectedDueToOverload();
                connectionStats.onRejectedDueToOverload();
                try {
                    sendSimpleResponse(ctx, streamId, "503 Service Unavailable", 503);
                } catch (Exception e) {
                    ctx.close();
                } finally {
                    server.stats.onRequestEnded(muReq);
                    connectionStats.onRequestEnded(muReq);
                }
            }
        };
        nettyHandlerAdapter.onHeaders(addedToExecutorCallback, asyncContext, muHeaders);
    }

    static CharSequence compressionToUse(Headers requestHeaders) {
        for (ParameterizedHeaderWithValue encVal : requestHeaders.acceptEncoding()) {
            String enc = encVal.value();
            if (HttpHeaderValues.GZIP.contentEqualsIgnoreCase(enc)) {
                return HeaderValues.GZIP;
            }
            if (HttpHeaderValues.DEFLATE.contentEqualsIgnoreCase(enc)) {
                return HeaderValues.DEFLATE;
            }
        }
        return null;
    }

    @Override
    public void onHeadersRead(ChannelHandlerContext ctx, int streamId, io.netty.handler.codec.http2.Http2Headers headers, int streamDependency,
                              short weight, boolean exclusive, int padding, boolean endOfStream) {
        onHeadersRead(ctx, streamId, headers, padding, endOfStream);
    }

    @Override
    public void onPriorityRead(ChannelHandlerContext ctx, int streamId, int streamDependency,
                               short weight, boolean exclusive) {
    }

    @Override
    public void onRstStreamRead(ChannelHandlerContext ctx, int streamId, long errorCode) {
        AsyncContext asyncContext = contexts.remove(streamId);
        if (asyncContext != null) {
            asyncContext.onCancelled(false);
        }
    }

    @Override
    public void onSettingsAckRead(ChannelHandlerContext ctx) {
    }

    @Override
    public void onSettingsRead(ChannelHandlerContext ctx, Http2Settings settings) {
    }

    @Override
    public void onPingRead(ChannelHandlerContext ctx, long data) {
    }

    @Override
    public void onPingAckRead(ChannelHandlerContext ctx, long data) {
    }

    @Override
    public void onPushPromiseRead(ChannelHandlerContext ctx, int streamId, int promisedStreamId,
                                  io.netty.handler.codec.http2.Http2Headers headers, int padding) {
    }

    @Override
    public void onGoAwayRead(ChannelHandlerContext ctx, int lastStreamId, long errorCode, ByteBuf debugData) {
        closeAllAndDisconnect(ctx, null);
    }

    @Override
    public void onWindowUpdateRead(ChannelHandlerContext ctx, int streamId, int windowSizeIncrement) {
    }

    @Override
    public void onUnknownFrame(ChannelHandlerContext ctx, byte frameType, int streamId,
                               Http2Flags flags, ByteBuf payload) {
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent) {
            closeAllAndDisconnect(ctx, Http2Error.NO_ERROR);
        }
    }

    @Override
    public String protocol() {
        return "HTTP/2";
    }

    @Override
    public boolean isHttps() {
        return true;
    }

    @Override
    public String httpsProtocol() {
        return Http1Connection.getSslSession(nettyContext).getProtocol();
    }

    @Override
    public String cipher() {
        return Http1Connection.getSslSession(nettyContext).getCipherSuite();
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
        return connectionStats.activeRequests();
    }

    @Override
    public Set<MuWebSocket> activeWebsockets() {
        return Collections.emptySet();
    }
}

