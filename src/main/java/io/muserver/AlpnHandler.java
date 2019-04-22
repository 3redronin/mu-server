package io.muserver;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;

import java.util.concurrent.atomic.AtomicReference;

class AlpnHandler extends ApplicationProtocolNegotiationHandler {
    private final NettyHandlerAdapter nettyHandlerAdapter;
    private final MuStatsImpl stats;
    private final AtomicReference<MuServer> serverRef;
    private final String proto;
    private final MuServerBuilder.ServerSettings settings;

    AlpnHandler(NettyHandlerAdapter nettyHandlerAdapter, MuStatsImpl stats, AtomicReference<MuServer> serverRef, String proto, MuServerBuilder.ServerSettings settings) {
        super(ApplicationProtocolNames.HTTP_1_1);
        this.nettyHandlerAdapter = nettyHandlerAdapter;
        this.stats = stats;
        this.serverRef = serverRef;
        this.proto = proto;
        this.settings = settings;
    }

    @Override
    protected void configurePipeline(ChannelHandlerContext ctx, String protocol) {
        if (ApplicationProtocolNames.HTTP_2.equals(protocol)) {
            ctx.pipeline().addLast(new Http2ConnectionBuilder(serverRef, nettyHandlerAdapter, stats, settings).build());
            return;
        }

        if (ApplicationProtocolNames.HTTP_1_1.equals(protocol)) {
            MuServerBuilder.setupHttp1Pipeline(ctx.pipeline(), settings, nettyHandlerAdapter, stats, serverRef, proto);
            return;
        }

        throw new IllegalStateException("unknown protocol: " + protocol);
    }
}
