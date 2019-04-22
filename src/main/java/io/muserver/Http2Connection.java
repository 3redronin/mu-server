package io.muserver;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http2.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

import static io.muserver.Http1Connection.STATE_ATTRIBUTE;
import static io.netty.buffer.Unpooled.copiedBuffer;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * A simple handler that responds with the message "Hello World!".
 */
public final class Http2Connection extends Http2ConnectionHandler implements Http2FrameListener {
    private static final Logger log = LoggerFactory.getLogger(Http2Connection.class);

    private final AtomicReference<MuServer> serverRef;
    private final NettyHandlerAdapter nettyHandlerAdapter;
    private final MuStatsImpl stats;

    Http2Connection(Http2ConnectionDecoder decoder, Http2ConnectionEncoder encoder,
                    Http2Settings initialSettings, AtomicReference<MuServer> serverRef, NettyHandlerAdapter nettyHandlerAdapter, MuStatsImpl stats) {
        super(decoder, encoder, initialSettings);
        this.serverRef = serverRef;
        this.nettyHandlerAdapter = nettyHandlerAdapter;
        this.stats = stats;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        Http1Connection.State state = ctx.channel().attr(STATE_ATTRIBUTE).get();
        if (state != null) {
            log.debug(cause.getClass().getName() + " (" + cause.getMessage() + ") for " + ctx + " so will disconnect this client");
            state.asyncContext.onCancelled(true);
        } else {
            log.debug("Exception for unknown ctx " + ctx, cause);
        }
        ctx.close();
    }

    private ChannelFuture sendSimpleResponse(ChannelHandlerContext ctx, int streamId, String message, int code) {
        byte[] bytes = message.getBytes(UTF_8);
        ByteBuf content = copiedBuffer(bytes) ;

        Http2Headers headers = new DefaultHttp2Headers();
        headers.status(String.valueOf(code));
        headers.set(HeaderNames.CONTENT_TYPE, ContentTypes.TEXT_PLAIN_UTF8);
        headers.set(HeaderNames.CONTENT_LENGTH, String.valueOf(bytes.length));
        encoder().writeHeaders(ctx, streamId, headers, 0, false, ctx.newPromise());
        return Http2Response.writeToChannel(ctx, encoder(), streamId, content, true);
    }


    @Override
    public int onDataRead(ChannelHandlerContext ctx, int streamId, ByteBuf data, int padding, boolean endOfStream) {
        int processed = data.readableBytes() + padding;

        Http1Connection.State state = ctx.channel().attr(STATE_ATTRIBUTE).get();
        if (state == null) {
            log.debug("Got a chunk of message for an unknown request. This can happen when a request is rejected based on headers, and then the rejected body arrives.");
        } else {
            if (data.capacity() > 0) {
                ByteBuf copy = data.copy();
                ByteBuffer byteBuffer = ByteBuffer.allocate(data.capacity());
                copy.readBytes(byteBuffer).release();
                byteBuffer.flip();
                state.handler.onRequestData(state.asyncContext, byteBuffer);
            }
            if (endOfStream) {
                state.handler.onRequestComplete(state.asyncContext);
            }
        }
        return processed;
    }

    @Override
    public void onHeadersRead(ChannelHandlerContext ctx, int streamId,
                              Http2Headers headers, int padding, boolean endOfStream) {

        HttpMethod nettyMeth = HttpMethod.valueOf(headers.method().toString().toUpperCase());
        Method muMethod;
        try {
            muMethod = Method.fromNetty(nettyMeth);
        } catch (IllegalArgumentException e) {
            sendSimpleResponse(ctx, streamId, "405 Method Not Allowed", 405);
            return;
        }

        final String uri = headers.path().toString();
        HttpRequest nettyReq = new Http2To1RequestAdapter(streamId, nettyMeth, uri, headers);
        boolean hasRequestBody = !endOfStream;
        if (hasRequestBody) {
            long bodyLen = headers.getLong(HeaderNames.CONTENT_LENGTH, -1L);
            if (bodyLen == 0) {
                hasRequestBody = false;
            }
        }
        H2Headers muHeaders = new H2Headers(headers, hasRequestBody);
        NettyRequestAdapter muReq = new NettyRequestAdapter(ctx.channel(), nettyReq, muHeaders, serverRef, muMethod, "https", uri, true, headers.authority().toString());

        stats.onRequestStarted(muReq);

        Http2Response resp = new Http2Response(ctx, muReq, new H2Headers(), encoder(), streamId);

        AsyncContext asyncContext = new AsyncContext(muReq, resp, stats);
        ctx.channel().attr(STATE_ATTRIBUTE).set(new Http1Connection.State(asyncContext, nettyHandlerAdapter));
        nettyHandlerAdapter.onHeaders(asyncContext, muHeaders);
    }

    @Override
    public void onHeadersRead(ChannelHandlerContext ctx, int streamId, Http2Headers headers, int streamDependency,
                              short weight, boolean exclusive, int padding, boolean endOfStream) {
        onHeadersRead(ctx, streamId, headers, padding, endOfStream);
    }

    @Override
    public void onPriorityRead(ChannelHandlerContext ctx, int streamId, int streamDependency,
                               short weight, boolean exclusive) {
    }

    @Override
    public void onRstStreamRead(ChannelHandlerContext ctx, int streamId, long errorCode) {
        Http1Connection.State state = ctx.channel().attr(STATE_ATTRIBUTE).get();
        if (state != null) {
            state.asyncContext.onCancelled(false);
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
                                  Http2Headers headers, int padding) {
    }

    @Override
    public void onGoAwayRead(ChannelHandlerContext ctx, int lastStreamId, long errorCode, ByteBuf debugData) {
        Http1Connection.State state = ctx.channel().attr(STATE_ATTRIBUTE).get();
        if (state != null) {
            state.asyncContext.onCancelled(true);
        }
    }

    @Override
    public void onWindowUpdateRead(ChannelHandlerContext ctx, int streamId, int windowSizeIncrement) {
    }

    @Override
    public void onUnknownFrame(ChannelHandlerContext ctx, byte frameType, int streamId,
                               Http2Flags flags, ByteBuf payload) {
    }

}

