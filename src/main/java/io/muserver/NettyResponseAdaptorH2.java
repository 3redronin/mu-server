package io.muserver;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;

class NettyResponseAdaptorH2 extends NettyResponseAdaptor {
    private final ChannelHandlerContext ctx;
    private final H2Headers headers;
    private final Http2ConnectionEncoder encoder;
    private final int streamId;

    NettyResponseAdaptorH2(ChannelHandlerContext ctx, NettyRequestAdapter request, H2Headers headers, Http2ConnectionEncoder encoder, int streamId) {
        super(request, headers, 2);
        this.ctx = ctx;
        this.headers = headers;
        this.encoder = encoder;
        this.streamId = streamId;
    }

    @Override
    protected ChannelFuture writeToChannel(boolean isLast, ByteBuf content) {
        ChannelPromise channelPromise = ctx.newPromise();
        ctx.executor().execute(() -> encoder.writeData(ctx, streamId, content, 0, isLast, channelPromise));
        ctx.channel().flush();
        return channelPromise;
    }

    @Override
    protected void startStreaming() {
        super.startStreaming();
        writeHeaders(false);
    }

    private void writeHeaders(boolean isEnd) {
        headers.entries.status(httpStatus().codeAsText());
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
}
