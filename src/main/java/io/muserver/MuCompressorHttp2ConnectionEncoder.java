package io.muserver;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http2.CompressorHttp2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.util.AttributeKey;

class MuCompressorHttp2ConnectionEncoder extends CompressorHttp2ConnectionEncoder {

    static final AttributeKey<Object> SHOULD_COMPRESS = AttributeKey.newInstance("compress");
    static final Object TOKEN = new Object();

    MuCompressorHttp2ConnectionEncoder(Http2ConnectionEncoder delegate, int compressionLevel, int windowBits, int memLevel) {
        super(delegate, compressionLevel, windowBits, memLevel);
    }

    @Override
    protected EmbeddedChannel newContentCompressor(ChannelHandlerContext ctx, CharSequence contentEncoding) throws Http2Exception {
        Object should = ctx.channel().attr(SHOULD_COMPRESS).getAndSet(null);
        if (should == null) {
            return null;
        }
        return super.newContentCompressor(ctx, contentEncoding);
    }
}
