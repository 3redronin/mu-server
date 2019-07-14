package io.muserver;

import io.netty.handler.codec.http2.*;

import java.util.concurrent.atomic.AtomicReference;

class Http2ConnectionBuilder
    extends AbstractHttp2ConnectionHandlerBuilder<Http2Connection, Http2ConnectionBuilder> {

    private final AtomicReference<MuServer> serverRef;
    private final NettyHandlerAdapter nettyHandlerAdapter;
    private final MuStatsImpl stats;
    private final ServerSettings settings;

    Http2ConnectionBuilder(AtomicReference<MuServer> serverRef, NettyHandlerAdapter nettyHandlerAdapter, MuStatsImpl stats, ServerSettings settings) {
        this.serverRef = serverRef;
        this.nettyHandlerAdapter = nettyHandlerAdapter;
        this.stats = stats;
        this.settings = settings;
    }

    @Override
    public Http2Connection build() {
        initialSettings().maxHeaderListSize(settings.maxHeadersSize);
        return super.build();
    }

    @Override
    protected Http2Connection build(Http2ConnectionDecoder decoder, Http2ConnectionEncoder encoder,
                                    Http2Settings initialSettings) {
        if (settings.gzipEnabled) {
            // using the delegate lets us intercept the writeHeaders, which is needed for a hack
            MuGzipHttp2ConnectionEncoder delegate = new MuGzipHttp2ConnectionEncoder(encoder);
            encoder = new MuCompressorHttp2ConnectionEncoder(delegate, CompressorHttp2ConnectionEncoder.DEFAULT_COMPRESSION_LEVEL, CompressorHttp2ConnectionEncoder.DEFAULT_WINDOW_BITS, CompressorHttp2ConnectionEncoder.DEFAULT_MEM_LEVEL);
        }
        Http2Connection handler = new Http2Connection(decoder, encoder, initialSettings, serverRef, nettyHandlerAdapter, stats, settings);
        frameListener(handler);
        return handler;
    }
}
