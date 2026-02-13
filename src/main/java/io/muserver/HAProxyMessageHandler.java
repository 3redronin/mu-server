package io.muserver;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import io.netty.util.AttributeKey;

class HAProxyMessageHandler extends SimpleChannelInboundHandler<HAProxyMessage> {

    static final AttributeKey<ProxiedConnectionInfo> HA_PROXY_INFO = AttributeKey.valueOf("HA_PROXY_INFO");

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, HAProxyMessage msg) {
        ProxiedConnectionInfoImpl proxyConnectionInfo = ProxiedConnectionInfoImpl.fromNetty(msg);
        ctx.channel().attr(HA_PROXY_INFO).set(proxyConnectionInfo);
    }
}
