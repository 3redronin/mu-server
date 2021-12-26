package io.muserver;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http2.*;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;

import static io.netty.buffer.Unpooled.EMPTY_BUFFER;
import static io.netty.buffer.Unpooled.copiedBuffer;
import static java.nio.charset.StandardCharsets.UTF_8;

abstract class Http2ConnectionFlowControl extends Http2ConnectionHandler implements Http2FrameListener {

    private static class DataReadData {
        private final ByteBuf data;
        private final int padding;
        private final boolean endOfStream;

        private DataReadData(ByteBuf data, int padding, boolean endOfStream) {
            this.data = data;
            this.padding = padding;
            this.endOfStream = endOfStream;
        }
    }

    private final Map<Integer, Queue<DataReadData>> buffer = new HashMap<>();
    private final Map<Integer, Boolean> wantsToRead = new HashMap<>();

    protected Http2ConnectionFlowControl(Http2ConnectionDecoder decoder, Http2ConnectionEncoder encoder, Http2Settings initialSettings) {
        super(decoder, encoder, initialSettings);
    }

    protected void read(ChannelHandlerContext ctx, int streamId) {
        if (!ctx.executor().inEventLoop()) {
            ctx.executor().execute(() -> read(ctx, streamId));
            return;
        }
        wantsToRead.put(streamId, true);
        ctx.executor().submit(() -> sendItMaybe(ctx, streamId));
    }

    private void sendItMaybe(ChannelHandlerContext ctx, int streamId) {
        if (ctx.channel().isActive()) {
            Boolean wantsIt = wantsToRead.get(streamId);
            if (wantsIt != null && wantsIt) {
                Queue<DataReadData> queue = buffer.get(streamId);
                if (queue != null) {
                    DataReadData msg = queue.poll();
                    if (msg != null) {
                        wantsToRead.put(streamId, false);
                        onDataRead0(ctx, streamId, msg.data, msg.padding, msg.endOfStream);
                        msg.data.release();
                    }
                }
            }
        }
    }

    protected abstract void onDataRead0(ChannelHandlerContext ctx, int streamId, ByteBuf data, int padding, boolean endOfStream);

    @Override
    public int onDataRead(ChannelHandlerContext ctx, int streamId, ByteBuf data, int padding, boolean endOfStream) {
        Queue<DataReadData> buf = buffer.computeIfAbsent(streamId, integer -> new LinkedList<>());
        buf.add(new DataReadData(data.retain(), padding, endOfStream));
        sendItMaybe(ctx, streamId);
        return 0;
    }

    protected void cleanup() {
        if (!wantsToRead.isEmpty()) {
            wantsToRead.clear();
        }
        if (buffer.size() > 0) {
            for (Queue<DataReadData> value : buffer.values()) {
                for (DataReadData dataReadData : value) {
                    dataReadData.data.release();
                }
            }
            buffer.clear();
        }
    }

    protected void cleanStream(int streamId) {
        wantsToRead.remove(streamId);
        cleanBuffer(streamId);
    }

    protected void cleanBuffer(int streamId) {
        Queue<DataReadData> removed = buffer.remove(streamId);
        if (removed != null) {
            if (!removed.isEmpty()) {
                for (DataReadData dat : removed) {
                    dat.data.release();
                }
                removed.clear();
            }
        }
    }

    @Override
    protected void handlerRemoved0(ChannelHandlerContext ctx) throws Exception {
        cleanup();
        super.handlerRemoved0(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        cleanup();
        super.channelInactive(ctx);
    }
}

final class Http2Connection extends Http2ConnectionFlowControl implements HttpConnection {
    private static final Logger log = LoggerFactory.getLogger(Http2Connection.class);

    private final MuServerImpl server;
    private final NettyHandlerAdapter nettyHandlerAdapter;
    private final ConcurrentHashMap<Integer, HttpExchange> exchanges = new ConcurrentHashMap<>();
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
        server.onConnectionStarted(this);
        super.handlerAdded(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        server.stats.onConnectionClosed();
        server.onConnectionEnded(this);
        super.channelInactive(ctx);
    }


    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("exception caught!", cause);
        closeAllAndDisconnect(ctx, Http2Error.INTERNAL_ERROR, ResponseState.ERRORED);
    }

    private void closeAllAndDisconnect(ChannelHandlerContext ctx, Http2Error error, ResponseState reason) {
        if (error != null) {
            encoder().writeGoAway(ctx, lastStreamId, error.code(), EMPTY_BUFFER, ctx.channel().voidPromise());
        }
        cleanup();
        ctx.close();
    }

    private ChannelFuture sendSimpleResponse(ChannelHandlerContext ctx, int streamId, String message, int code) {
        byte[] bytes = message.getBytes(UTF_8);
        ByteBuf content = copiedBuffer(bytes);

        io.netty.handler.codec.http2.Http2Headers headers = new DefaultHttp2Headers();
        headers.status(String.valueOf(code));
        headers.set(HeaderNames.CONTENT_TYPE, ContentTypes.TEXT_PLAIN_UTF8);
        headers.set(HeaderNames.CONTENT_LENGTH, String.valueOf(bytes.length));
        encoder().writeHeaders(ctx, streamId, headers, 0, false, ctx.voidPromise());
        return Http2Response.writeAndFlushToChannel(ctx, encoder(), streamId, content, true);
    }

    @Override
    protected void cleanStream(int streamId) {
        super.cleanStream(streamId);
        exchanges.remove(streamId);
    }

    @Override
    protected void cleanup() {
        super.cleanup();
        if (!exchanges.isEmpty()) {
            for (Integer streamId : exchanges.keySet()) {
                cancelExchange(streamId);
            }
        }
    }

    @Override
    public int onDataRead(ChannelHandlerContext ctx, int streamId, ByteBuf data, int padding, boolean endOfStream) {
        if (!exchanges.containsKey(streamId)) {
            // there was exception in onHeadersRead() e.g. '413 Payload Too Large'
            super.cleanBuffer(streamId);
            return data.readableBytes() + padding;
        }
        return super.onDataRead(ctx, streamId, data, padding, endOfStream);
    }

    @Override
    public void onHeadersRead(ChannelHandlerContext ctx, int streamId,
                              io.netty.handler.codec.http2.Http2Headers headers, int padding, boolean endOfStream) throws Http2Exception {
        lastStreamId = streamId;

        try {
            HttpMethod nettyMeth = HttpMethod.valueOf(headers.method().toString().toUpperCase());
            Method muMethod = HttpExchange.getMethod(nettyMeth);

            String uri = HttpExchange.getRelativeUrl(headers.path().toString());
            ServerSettings settings = server.settings();
            if (uri.length() > settings.maxUrlSize) {
                throw new InvalidHttpRequestException(414, "414 Request-URI Too Long");
            }

            HttpRequest nettyReq = new Http2To1RequestAdapter(streamId, nettyMeth, uri, headers);
            boolean hasRequestBody = !endOfStream;
            if (hasRequestBody) {
                long bodyLen = headers.getLong(HeaderNames.CONTENT_LENGTH, -1L);
                if (bodyLen == 0) {
                    hasRequestBody = false;
                } else if (bodyLen > settings.maxRequestSize) {
                    throw new InvalidHttpRequestException(413, "413 Payload Too Large");
                }
            }
            Http2Headers muHeaders = new Http2Headers(headers, hasRequestBody);
            String host = headers.authority().toString();
            muHeaders.set(HeaderNames.HOST, host);
            NettyRequestAdapter muReq = new NettyRequestAdapter(ctx, nettyReq, muHeaders, muMethod, "https", uri, host);

            Http2Response resp = new Http2Response(ctx, muReq, new Http2Headers(), encoder(), streamId, settings);
            HttpExchange httpExchange = new HttpExchange(this, ctx, muReq, resp, streamId);
            resp.setExchange(httpExchange);
            muReq.setExchange(httpExchange);


            if (settings.block(muReq)) {
                throw new InvalidHttpRequestException(429, "429 Too Many Requests");
            }

            resp.addChangeListener((exchange, newState) -> {
                if (newState.endState()) {
                    nettyHandlerAdapter.onResponseComplete(exchange, server.stats, connectionStats);
                }
            });
            exchanges.put(streamId, httpExchange);
            httpExchange.addChangeListener((exchange, newState) -> {
                if (newState.endState()) {
                    muReq.cleanup();
                    cleanStream(streamId);
                    if (newState == HttpExchangeState.ERRORED) {
                        resetStream(ctx, streamId, Http2Error.INTERNAL_ERROR.code(), ctx.voidPromise());
                        ctx.flush();
                    }
                }
            });

            muReq.addChangeListener((exchange, newState) -> {
                if (newState == RequestState.RECEIVING_BODY) {
                    read(ctx, streamId);
                }
            });

            if (endOfStream) {
                muReq.setState(RequestState.COMPLETE);
                super.onDataRead(ctx, streamId, EMPTY_BUFFER, 0, true);
            }

            try {
                server.stats.onRequestStarted(httpExchange.request);
                connectionStats.onRequestStarted(httpExchange.request);
                nettyHandlerAdapter.onHeaders(httpExchange);
            } catch (RejectedExecutionException e) {
                server.stats.onRequestEnded(httpExchange.request);
                connectionStats.onRequestEnded(httpExchange.request);
                log.warn("Could not service " + httpExchange.request + " because the thread pool is full so sending a 503");
                throw new InvalidHttpRequestException(503, "503 Service Unavailable");
            }

        } catch (InvalidHttpRequestException ihr) {
            if (ihr.code == 429 || ihr.code == 503) {
                connectionStats.onRejectedDueToOverload();
                server.stats.onRejectedDueToOverload();
            } else {
                connectionStats.onInvalidRequest();
                server.stats.onInvalidRequest();
            }
            sendSimpleResponse(ctx, streamId, ihr.getMessage(), ihr.code);
        }
    }

    @Override
    public void onDataRead0(ChannelHandlerContext ctx, int streamId, ByteBuf data, int padding, boolean endOfStream) {
        int dataSize = data.readableBytes();
        int consumed = dataSize + padding;
        boolean empty = dataSize == 0;

        HttpExchange httpExchange = exchanges.get(streamId);
        if (httpExchange == null) {
            log.debug("Got a chunk of message for an unknown request. This can happen when a request is rejected based on headers, and then the rejected body arrives.");
        } else {
            HttpContent msg;
            if (endOfStream) {
                msg = empty ? DefaultLastHttpContent.EMPTY_LAST_CONTENT : new DefaultLastHttpContent(data, false);
            } else {
                msg = new DefaultHttpContent(data);
            }
            data.retain();
            DoneCallback doneCallback = error -> {

                Http2Stream stream = this.connection().stream(streamId);
                if (stream != null && this.decoder().flowController().consumeBytes(stream, consumed)) {
                    ctx.flush();
                }

                data.release();
                if (error != null) {
                    ctx.fireUserEventTriggered(new MuExceptionFiredEvent(httpExchange, streamId, error));
                } else if (!endOfStream) {
                    read(ctx, streamId);
                }
                // error == null && endOfStream == true here, then do nothing
                // as it just indicate the request is finished, no more data to read.
            };
            httpExchange.onMessage(ctx, msg, error -> {
                if (ctx.executor().inEventLoop()) {
                    doneCallback.onComplete(error);
                } else {
                    ctx.executor().execute(() -> {
                        try {
                            doneCallback.onComplete(error);
                        } catch (Exception e) {
                            log.debug("Error from doneCallback, e");
                        }
                    });
                }
            });
        }
    }

    @Override
    protected void onStreamError(ChannelHandlerContext ctx, boolean outbound, Throwable cause, Http2Exception.StreamException http2Ex) {
        HttpExchange httpExchange = exchanges.get(http2Ex.streamId());
        if (httpExchange != null) {
            Throwable toy = cause;
            while (toy instanceof Http2Exception) {
                toy = toy.getCause();
            }
            if (toy == null) {
                toy = cause;
            }
            try {
                if (httpExchange.onException(ctx, toy)) {
                    super.onStreamError(ctx, outbound, cause, http2Ex);
                }
            } catch (Throwable e) {
                log.warn("Unexpected exception for " + httpExchange + " .onException " + toy, e);
                super.onStreamError(ctx, outbound, cause, http2Ex);
            }
        } else {
            super.onStreamError(ctx, outbound, cause, http2Ex);
        }
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
                              short weight, boolean exclusive, int padding, boolean endOfStream) throws Http2Exception {
        onHeadersRead(ctx, streamId, headers, padding, endOfStream);
    }

    @Override
    public void onPriorityRead(ChannelHandlerContext ctx, int streamId, int streamDependency,
                               short weight, boolean exclusive) {
    }

    @Override
    public void onRstStreamRead(ChannelHandlerContext ctx, int streamId, long errorCode) {
        cancelExchange(streamId);
    }

    /**
     * This method will cancel exchange by streamId if it still alive.
     *
     * @param streamId http2 stream Id
     */
    private void cancelExchange(int streamId) {
        /*
          It does NOT removed the live exchange from exchanges Map directly here, the side effect of
          'httpExchange.onCancelled(ResponseState.ERRORED)' call, (e.g. HttpExchangeStateChangeListener)
          will do the removal.
         */
        HttpExchange httpExchange = exchanges.get(streamId);
        if (httpExchange != null) {
            httpExchange.onCancelled(ResponseState.ERRORED);
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
        closeAllAndDisconnect(ctx, null, ResponseState.CLIENT_DISCONNECTED);
    }

    @Override
    public void onWindowUpdateRead(ChannelHandlerContext ctx, int streamId, int windowSizeIncrement) {
    }

    @Override
    public void onUnknownFrame(ChannelHandlerContext ctx, byte frameType, int streamId,
                               Http2Flags flags, ByteBuf payload) {
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent ise = (IdleStateEvent) evt;
            if (ise.state() != IdleState.READER_IDLE) {
                log.info("Closed idle connection to " + remoteAddress);
                closeAllAndDisconnect(ctx, Http2Error.NO_ERROR, ResponseState.TIMED_OUT);
            }
        } else if (evt instanceof MuExceptionFiredEvent) {
            MuExceptionFiredEvent mefe = (MuExceptionFiredEvent) evt;
            // TODO: does outbound need to be set accurately?
            Throwable error = mefe.error;
            if (mefe.streamId > 0) {
                error = Http2Exception.streamError(mefe.streamId, Http2Error.INTERNAL_ERROR, error, "Error handling %s", mefe.exchange);
            }
            onError(ctx, false, error);
        }
        super.userEventTriggered(ctx, evt);
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

    @Override
    public MuServer server() {
        return server;
    }

}

