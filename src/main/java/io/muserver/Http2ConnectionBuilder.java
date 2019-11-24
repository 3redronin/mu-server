package io.muserver;

import io.netty.handler.codec.http2.*;

class Http2ConnectionBuilder
    extends AbstractHttp2ConnectionHandlerBuilder<Http2Connection, Http2ConnectionBuilder> {

    private final MuServerImpl server;
    private final NettyHandlerAdapter nettyHandlerAdapter;

    Http2ConnectionBuilder(MuServerImpl server, NettyHandlerAdapter nettyHandlerAdapter) {
        this.server = server;
        this.nettyHandlerAdapter = nettyHandlerAdapter;
    }

    @Override
    public Http2Connection build() {
        initialSettings().maxHeaderListSize(server.settings().maxHeadersSize);
        return super.build();
    }

    @Override
    protected Http2Connection build(Http2ConnectionDecoder decoder, Http2ConnectionEncoder encoder,
                                    Http2Settings initialSettings) {
        if (server.settings().gzipEnabled) {
            // using the delegate lets us intercept the writeHeaders, which is needed for a hack
            MuGzipHttp2ConnectionEncoder delegate = new MuGzipHttp2ConnectionEncoder(encoder);
            encoder = new MuCompressorHttp2ConnectionEncoder(delegate, CompressorHttp2ConnectionEncoder.DEFAULT_COMPRESSION_LEVEL, CompressorHttp2ConnectionEncoder.DEFAULT_WINDOW_BITS, CompressorHttp2ConnectionEncoder.DEFAULT_MEM_LEVEL);
        }
        Http2Connection handler = new Http2Connection(decoder, encoder, initialSettings, server, nettyHandlerAdapter);
        frameListener(handler);
        return handler;
    }
}
