package io.muserver;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http2.CompressorHttp2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2Exception;

class MuCompressorHttp2ConnectionEncoder extends CompressorHttp2ConnectionEncoder {

    MuCompressorHttp2ConnectionEncoder(Http2ConnectionEncoder delegate, int compressionLevel, int windowBits, int memLevel) {
        super(delegate, compressionLevel, windowBits, memLevel);
    }

    @Override
    protected EmbeddedChannel newContentCompressor(ChannelHandlerContext ctx, CharSequence contentEncoding) throws Http2Exception {
        CharSequence actual = MuGzipHttp2ConnectionEncoder.actualEncodingIfHasMuPrefix(contentEncoding);
        if (actual == null) {
            return null;
        }
        return super.newContentCompressor(ctx, actual);
    }

}
