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
        Http2Connection handler = new Http2Connection(decoder, encoder, initialSettings, server, nettyHandlerAdapter);
        frameListener(handler);
        return handler;
    }
}
