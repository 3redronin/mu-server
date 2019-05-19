package io.muserver;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.*;

class MuGzipHttp2ConnectionEncoder implements Http2ConnectionEncoder {
    private final Http2ConnectionEncoder encoder;

    MuGzipHttp2ConnectionEncoder(Http2ConnectionEncoder encoder) {
        this.encoder = encoder;
    }

    @Override
    public void lifecycleManager(Http2LifecycleManager lifecycleManager) {
        encoder.lifecycleManager(lifecycleManager);
    }

    @Override
    public Http2Connection connection() {
        return encoder.connection();
    }

    @Override
    public Http2RemoteFlowController flowController() {
        return encoder.flowController();
    }

    @Override
    public Http2FrameWriter frameWriter() {
        return encoder.frameWriter();
    }

    @Override
    public Http2Settings pollSentSettings() {
        return encoder.pollSentSettings();
    }

    @Override
    public void remoteSettings(Http2Settings settings) throws Http2Exception {
        encoder.remoteSettings(settings);
    }

    @Override
    public ChannelFuture writeHeaders(ChannelHandlerContext ctx, int streamId, io.netty.handler.codec.http2.Http2Headers headers, int padding, boolean endStream, ChannelPromise promise) {
        fixEncoding(headers);
        return encoder.writeHeaders(ctx, streamId, headers, padding, endStream, promise);
    }

    @Override
    public ChannelFuture writeHeaders(ChannelHandlerContext ctx, int streamId, io.netty.handler.codec.http2.Http2Headers headers, int streamDependency, short weight, boolean exclusive, int padding, boolean endStream, ChannelPromise promise) {
        fixEncoding(headers);
        return encoder.writeHeaders(ctx, streamId, headers, streamDependency, weight, exclusive, padding, endStream, promise);
    }

    private void fixEncoding(Http2Headers headers) {
        CharSequence seq = headers.get(HeaderNames.CONTENT_ENCODING);
        CharSequence actual = actualEncodingIfHasMuPrefix(seq);
        if (actual != null) {
            headers.set(HeaderNames.CONTENT_ENCODING, actual);
        }
    }

    static CharSequence actualEncodingIfHasMuPrefix(CharSequence seq) {
        if (seq != null) {
            int len = seq.length();
            if (len > 3 && seq.charAt(0) == 'm' && seq.charAt(1) == 'u' && seq.charAt(2) == '-') {
                return seq.subSequence(3, len);
            }
        }
        return null;
    }

    @Override
    public ChannelFuture writePriority(ChannelHandlerContext ctx, int streamId, int streamDependency, short weight, boolean exclusive, ChannelPromise promise) {
        return encoder.writePriority(ctx, streamId, streamDependency, weight, exclusive, promise);
    }

    @Override
    public ChannelFuture writeRstStream(ChannelHandlerContext ctx, int streamId, long errorCode, ChannelPromise promise) {
        return encoder.writeRstStream(ctx, streamId, errorCode, promise);
    }

    @Override
    public ChannelFuture writeSettings(ChannelHandlerContext ctx, Http2Settings settings, ChannelPromise promise) {
        return encoder.writeSettings(ctx, settings, promise);
    }

    @Override
    public ChannelFuture writeSettingsAck(ChannelHandlerContext ctx, ChannelPromise promise) {
        return encoder.writeSettingsAck(ctx, promise);
    }

    @Override
    public ChannelFuture writePing(ChannelHandlerContext ctx, boolean ack, long data, ChannelPromise promise) {
        return encoder.writePing(ctx, ack, data, promise);
    }

    @Override
    public ChannelFuture writePushPromise(ChannelHandlerContext ctx, int streamId, int promisedStreamId, Http2Headers headers, int padding, ChannelPromise promise) {
        return encoder.writePushPromise(ctx, streamId, promisedStreamId, headers, padding, promise);
    }

    @Override
    public ChannelFuture writeGoAway(ChannelHandlerContext ctx, int lastStreamId, long errorCode, ByteBuf debugData, ChannelPromise promise) {
        return encoder.writeGoAway(ctx, lastStreamId, errorCode, debugData, promise);
    }

    @Override
    public ChannelFuture writeWindowUpdate(ChannelHandlerContext ctx, int streamId, int windowSizeIncrement, ChannelPromise promise) {
        return encoder.writeWindowUpdate(ctx, streamId, windowSizeIncrement, promise);
    }

    @Override
    public ChannelFuture writeFrame(ChannelHandlerContext ctx, byte frameType, int streamId, Http2Flags flags, ByteBuf payload, ChannelPromise promise) {
        return encoder.writeFrame(ctx, frameType, streamId, flags, payload, promise);
    }

    @Override
    public Configuration configuration() {
        return encoder.configuration();
    }

    @Override
    public void close() {
        encoder.close();
    }

    @Override
    public ChannelFuture writeData(ChannelHandlerContext ctx, int streamId, ByteBuf data, int padding, boolean endStream, ChannelPromise promise) {
        return encoder.writeData(ctx, streamId, data, padding, endStream, promise);
    }
}
