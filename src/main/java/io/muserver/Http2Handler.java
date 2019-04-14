package io.muserver;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http2.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

import static io.muserver.Http1Handler.STATE_ATTRIBUTE;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;

/**
 * A simple handler that responds with the message "Hello World!".
 */
public final class Http2Handler extends Http2ConnectionHandler implements Http2FrameListener {
    private static final Logger log = LoggerFactory.getLogger(Http2Handler.class);

    private final AtomicReference<MuServer> serverRef;
    private final NettyHandlerAdapter nettyHandlerAdapter;
    private final MuStatsImpl stats;

    Http2Handler(Http2ConnectionDecoder decoder, Http2ConnectionEncoder encoder,
                 Http2Settings initialSettings, AtomicReference<MuServer> serverRef, NettyHandlerAdapter nettyHandlerAdapter, MuStatsImpl stats) {
        super(decoder, encoder, initialSettings);
        this.serverRef = serverRef;
        this.nettyHandlerAdapter = nettyHandlerAdapter;
        this.stats = stats;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        super.exceptionCaught(ctx, cause);
        cause.printStackTrace();
        ctx.close();
    }

    /**
     * Sends a "Hello World" DATA frame to the client.
     */
    private void sendResponse(ChannelHandlerContext ctx, int streamId, ByteBuf payload) {
        // Send a frame for the response status
        Http2Headers headers = new DefaultHttp2Headers().status(OK.codeAsText());
        encoder().writeHeaders(ctx, streamId, headers, 0, false, ctx.newPromise());
        encoder().writeData(ctx, streamId, payload, 0, true, ctx.newPromise());

        // no need to call flush as channelReadComplete(...) will take care of it.
    }

    @Override
    public int onDataRead(ChannelHandlerContext ctx, int streamId, ByteBuf data, int padding, boolean endOfStream) {
        int processed = data.readableBytes() + padding;

        Http1Handler.State state = ctx.channel().attr(STATE_ATTRIBUTE).get();
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

        if (endOfStream) {
            sendResponse(ctx, streamId, data.retain());
        }
        return processed;
    }

    @Override
    public void onHeadersRead(ChannelHandlerContext ctx, int streamId,
                              Http2Headers headers, int padding, boolean endOfStream) {

        HttpMethod nettyMeth = HttpMethod.valueOf(headers.method().toString().toUpperCase());
        Method muMethod = Method.fromNetty(nettyMeth);

        final String uri = headers.path().toString();
        HttpRequest nettyReq = new HttpRequest() {
            @Override
            public HttpMethod getMethod() {
                return nettyMeth;
            }

            @Override
            public HttpMethod method() {
                return nettyMeth;
            }

            @Override
            public HttpRequest setMethod(HttpMethod method) {
                throw new IllegalStateException("Can't set stuff");
            }

            @Override
            public String getUri() {
                return uri;
            }

            @Override
            public String uri() {
                return uri;
            }

            @Override
            public HttpRequest setUri(String uri) {
                throw new IllegalStateException("Can't set stuff");
            }

            @Override
            public HttpRequest setProtocolVersion(HttpVersion version) {
                throw new IllegalStateException("Can't set stuff");
            }

            @Override
            public HttpVersion getProtocolVersion() {
                return HttpVersion.valueOf("HTTP/2");
            }

            @Override
            public HttpVersion protocolVersion() {
                return HttpVersion.valueOf("HTTP/2");
            }

            @Override
            public HttpHeaders headers() {
                throw new IllegalStateException("blah");
            }

            @Override
            public DecoderResult getDecoderResult() {
                return DecoderResult.SUCCESS;
            }

            @Override
            public DecoderResult decoderResult() {
                return DecoderResult.SUCCESS;
            }

            @Override
            public void setDecoderResult(DecoderResult result) {
                throw new IllegalStateException("Can't set stuff");
            }
        };
        H2Headers muHeaders = new H2Headers(headers, !endOfStream);
        NettyRequestAdapter muReq = new NettyRequestAdapter(ctx.channel(), nettyReq, muHeaders, serverRef, muMethod, "https", uri, true, headers.authority().toString());
        NettyResponseAdaptorH2 resp = new NettyResponseAdaptorH2(ctx, muReq, new H2Headers(), encoder(), streamId);

        AsyncContext asyncContext = new AsyncContext(muReq, resp, stats);
        ctx.channel().attr(STATE_ATTRIBUTE).set(new Http1Handler.State(asyncContext, nettyHandlerAdapter));
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
    }

    @Override
    public void onWindowUpdateRead(ChannelHandlerContext ctx, int streamId, int windowSizeIncrement) {
    }

    @Override
    public void onUnknownFrame(ChannelHandlerContext ctx, byte frameType, int streamId,
                               Http2Flags flags, ByteBuf payload) {
        log.warn("UnknownFrame");
    }
}

