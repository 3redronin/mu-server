package io.muserver;

import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.ssl.SniHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.AttributeKey;
import io.netty.util.Mapping;

import javax.net.ssl.SSLParameters;
import java.util.function.Supplier;


public class MuSniHandler extends SniHandler {

    static final AttributeKey<String> SNI_HOSTNAME = AttributeKey.valueOf("SNI_HOSTNAME");

    public MuSniHandler(Supplier<Mapping<? super String, ? extends SslContext>> mappingProvider) {
        super(mappingProvider.get());
    }

    @Override
    protected void replaceHandler(ChannelHandlerContext ctx, String hostname, SslContext sslContext) throws Exception {
        ctx.channel().attr(SNI_HOSTNAME).set(hostname());
        super.replaceHandler(ctx, hostname, sslContext);
    }

    @Override
    protected SslHandler newSslHandler(SslContext context, ByteBufAllocator allocator) {
        SslHandler sslHandler = context.newHandler(allocator);
        SSLParameters params = sslHandler.engine().getSSLParameters();
        params.setUseCipherSuitesOrder(true);
        sslHandler.engine().setSSLParameters(params);
        return sslHandler;
    }
}
