package io.muserver;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;

class Http2Response extends NettyResponseAdaptor {

    private final ChannelHandlerContext ctx;
    private final Http2Headers headers;
    private final Http2ConnectionEncoder encoder;
    private final int streamId;
    private final ServerSettings settings;

    Http2Response(ChannelHandlerContext ctx, NettyRequestAdapter request, Http2Headers headers, Http2ConnectionEncoder encoder, int streamId, ServerSettings settings) {
        super(request, headers);
        this.ctx = ctx;
        this.headers = headers;
        this.encoder = encoder;
        this.streamId = streamId;
        this.settings = settings;
    }

    @Override
    protected ChannelFuture writeAndFlushToChannel(boolean isLast, ByteBuf content) {
        return writeAndFlushToChannel(ctx, encoder, streamId, content, isLast);
    }

    static ChannelFuture writeAndFlushToChannel(ChannelHandlerContext ctx, Http2ConnectionEncoder encoder, int streamId, ByteBuf content, boolean isLast) {
        assert ctx.executor().inEventLoop() : "Not in event loop";
        ChannelPromise channelPromise = ctx.newPromise();
        encoder.writeData(ctx, streamId, content, 0, isLast, channelPromise);
        ctx.channel().flush();
        return channelPromise;
    }

    @Override
    protected ChannelFuture startStreaming() {
        super.startStreaming();
        return writeHeaders(false);
    }

    @Override
    protected void onContentLengthMismatch() {
        throw new IllegalStateException("The declared content length for " + request + " was " + declaredLength + " bytes. " +
            "The current write is being aborted and the connection is being closed because it would have resulted in " +
            bytesStreamed + " bytes being sent.");
    }

    private ChannelFuture writeHeaders(boolean isEnd) {
        assert ctx.executor().inEventLoop() : "Not in event loop";
        headers.entries.status(httpStatus().codeAsText());

        if (settings.shouldCompress(headers.get(HeaderNames.CONTENT_LENGTH), headers.get(HeaderNames.CONTENT_TYPE))) {
            headers.set(HeaderNames.VARY, getVaryWithAE(headers.get(HeaderNames.VARY)));
            CharSequence toUse = Http2Connection.compressionToUse(request.headers());
            if (toUse != null && !headers.entries.contains(HeaderNames.CONTENT_ENCODING)) {
                // By setting the header value, the CompressorHttp2ConnectionEncoder added by the Http2ConnectionBuilder will encode the bytes.
                // The mu- prefix is what indicates to the compressor that we want to compress it, and MuGzipHttp2ConnectionEncoder removes the mu- prefix.
                headers.set(HeaderNames.CONTENT_ENCODING, "mu-" + toUse);
            }
        }
        ChannelFuture future = encoder.writeHeaders(ctx, streamId, headers.entries, 0, isEnd, ctx.voidPromise());
        if (isEnd) {
            ctx.channel().flush();
        }
        return future;
    }

    @Override
    protected ChannelFuture writeFullResponse(ByteBuf body) {
        writeHeaders(false);
        return writeAndFlushToChannel(true, body);
    }

    @Override
    protected ChannelFuture writeLastContentMarker() {
        return writeAndFlushToChannel(true, Unpooled.directBuffer(0));
    }

    @Override
    protected ChannelFuture sendEmptyResponse(boolean addContentLengthHeader) {
        if (addContentLengthHeader) {
            headers.set(HeaderNames.CONTENT_LENGTH, HeaderValues.ZERO);
        }
        return writeHeaders(true);
    }

    @Override
    public String toString() {
        return "Http2Response{" +
            "outputState=" + outputState() +
            ", status=" + status +
            "}";
    }

    @Override
    public HttpStatus statusValue() {
        return null;
    }

    @Override
    public void status(HttpStatus value) {

    }

    @Override
    public void sendInformationalResponse(HttpStatus status, Headers headers) {

    }
}
