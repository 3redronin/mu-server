package io.muserver;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;

class AlpnHandler extends ApplicationProtocolNegotiationHandler {
    private final NettyHandlerAdapter nettyHandlerAdapter;
    private final MuServerImpl server;
    private final String proto;

    AlpnHandler(NettyHandlerAdapter nettyHandlerAdapter, MuServerImpl server, String proto) {
        super(ApplicationProtocolNames.HTTP_1_1);
        this.nettyHandlerAdapter = nettyHandlerAdapter;
        this.server = server;
        this.proto = proto;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        super.handlerAdded(ctx);
    }

    @Override
    protected void configurePipeline(ChannelHandlerContext ctx, String protocol) {
        if (ApplicationProtocolNames.HTTP_2.equals(protocol)) {
            ctx.pipeline().addLast(new Http2ConnectionBuilder(server, nettyHandlerAdapter).build());
            return;
        }

        if (ApplicationProtocolNames.HTTP_1_1.equals(protocol)) {
            MuServerBuilder.setupHttp1Pipeline(ctx.pipeline(), nettyHandlerAdapter, server, proto);
            return;
        }

        throw new IllegalStateException("unknown protocol: " + protocol);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.close(); // don't call super as it logs an unwanted warning
    }

    @Override
    protected void handshakeFailure(ChannelHandlerContext ctx, Throwable cause) {
        ctx.close(); // don't call super as it logs an unwanted warning
    }
}
