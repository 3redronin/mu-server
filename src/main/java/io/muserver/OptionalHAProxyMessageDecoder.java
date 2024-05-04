package io.muserver;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.haproxy.HAProxyMessageDecoder;

import java.util.Arrays;
import java.util.List;

class OptionalHAProxyMessageDecoder extends ByteToMessageDecoder {
    static final String CHANNEL_NAME = "OptionalHAProxyMessageDecoder";

    private static final byte[] V1_PROXY_HEADER = new byte[]{'P', 'R', 'O', 'X', 'Y'};
    private static final byte[] V2_PROXY_HEADER = new byte[]{
        0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A, 0x51, 0x55, 0x49, 0x54, 0x0A, 0x02
    };

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (!checkProtocol(ctx, in, V2_PROXY_HEADER, 16)) {
            checkProtocol(ctx, in, V1_PROXY_HEADER, 8);
        }
        ctx.pipeline().remove(this);
    }

    private static boolean checkProtocol(ChannelHandlerContext ctx, ByteBuf in, byte[] proxyHeader, int minAllowedSize) {
        if (in.readableBytes() >= minAllowedSize) {
            in.markReaderIndex();
            byte[] prefix = new byte[proxyHeader.length];
            in.getBytes(in.readerIndex(), prefix);
            in.resetReaderIndex();

            if (Arrays.equals(prefix, proxyHeader)) {
                ctx.pipeline().addAfter(CHANNEL_NAME, "HAProxyMessageDecoder", new HAProxyMessageDecoder());
            }
            return true;
        }
        return false;
    }
}