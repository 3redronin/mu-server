package io.muserver;

import io.netty.handler.codec.http2.*;
import io.netty.handler.logging.LogLevel;

import java.util.concurrent.atomic.AtomicReference;

class Http2ConnectionBuilder
    extends AbstractHttp2ConnectionHandlerBuilder<Http2Connection, Http2ConnectionBuilder> {

    private static final Http2FrameLogger logger = new Http2FrameLogger(LogLevel.DEBUG, Http2Connection.class);
    private final AtomicReference<MuServer> serverRef;
    private final NettyHandlerAdapter nettyHandlerAdapter;
    private final MuStatsImpl stats;
    private final MuServerBuilder.ServerSettings settings;

    Http2ConnectionBuilder(AtomicReference<MuServer> serverRef, NettyHandlerAdapter nettyHandlerAdapter, MuStatsImpl stats, MuServerBuilder.ServerSettings settings) {
        this.serverRef = serverRef;
        this.nettyHandlerAdapter = nettyHandlerAdapter;
        this.stats = stats;
        this.settings = settings;
        frameLogger(logger);
    }

    @Override
    public Http2Connection build() {
        return super.build();
    }

    @Override
    protected Http2Connection build(Http2ConnectionDecoder decoder, Http2ConnectionEncoder encoder,
                                    Http2Settings initialSettings) {
        Http2Connection handler = new Http2Connection(decoder, encoder, initialSettings, serverRef, nettyHandlerAdapter, stats, settings);
        frameListener(handler);
        return handler;
    }
}
