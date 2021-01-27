package io.muserver;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class Http2Response extends NettyResponseAdaptor {
    private static final Logger log = LoggerFactory.getLogger(Http2Response.class);

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
    protected ChannelFuture writeToChannel(boolean isLast, ByteBuf content) {
        return writeToChannel(ctx, encoder, streamId, content, isLast);
    }

    static ChannelFuture writeToChannel(ChannelHandlerContext ctx, Http2ConnectionEncoder encoder, int streamId, ByteBuf content, boolean isLast) {
        ChannelPromise channelPromise = ctx.newPromise();
        if (ctx.executor().inEventLoop()) {
            writeToChannelForReal(ctx, encoder, streamId, content, isLast, channelPromise);
        } else {
            ctx.executor().execute(() -> writeToChannelForReal(ctx, encoder, streamId, content, isLast, channelPromise));
        }
        return channelPromise;
    }

    private static void writeToChannelForReal(ChannelHandlerContext ctx, Http2ConnectionEncoder encoder, int streamId, ByteBuf content, boolean isLast, ChannelPromise channelPromise) {

        encoder.writeData(ctx, streamId, content, 0, isLast, channelPromise);
        ctx.channel().flush();
    }

    @Override
    protected boolean onBadRequestSent() {
        return false; // the stream is bad, but the connection is fine. Doesn't matter.
    }

    @Override
    protected void startStreaming() {
        super.startStreaming();
        writeHeaders(false);
    }

    @Override
    protected void onContentLengthMismatch() {
        throw new IllegalStateException("The declared content length for " + request + " was " + declaredLength + " bytes. " +
            "The current write is being aborted and the connection is being closed because it would have resulted in " +
            bytesStreamed + " bytes being sent.");
    }

    private void writeHeaders(boolean isEnd) {
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

        if (ctx.executor().inEventLoop()) {
            writeHeadersForReal(isEnd);
        } else {
            ctx.executor().execute(() -> writeHeadersForReal(isEnd));
        }
    }

    private void writeHeadersForReal(boolean isEnd) {
        encoder.writeHeaders(ctx, streamId, headers.entries, 0, isEnd, ctx.newPromise());
        if (isEnd) {
            ctx.channel().flush();
        }
    }

    @Override
    protected void writeFullResponse(ByteBuf body) {
        writeHeaders(false);
        writeToChannel(true, body);
    }

    @Override
    protected boolean connectionOpen() {
        return ctx.channel().isOpen();
    }

    @Override
    protected ChannelFuture closeConnection() {
        return ctx.channel().close();
    }

    @Override
    protected ChannelFuture writeLastContentMarker() {
        return writeToChannel(true, Unpooled.directBuffer(0));
    }

    @Override
    protected void sendEmptyResponse(boolean addContentLengthHeader) {
        if (addContentLengthHeader) {
            headers.set(HeaderNames.CONTENT_LENGTH, HeaderValues.ZERO);
        }
        writeHeaders(true);
    }


    @Override
    protected void writeRedirectResponse() {
        writeHeaders(true);
    }

    @Override
    public String toString() {
        return "Http2Response{" +
            "outputState=" + outputState() +
            ", status=" + status +
            "}";
    }
}
