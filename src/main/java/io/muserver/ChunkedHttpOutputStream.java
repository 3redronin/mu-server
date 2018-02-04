package io.muserver;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.HttpMessage;

import java.io.IOException;
import java.io.OutputStream;

class ChunkedHttpOutputStream extends OutputStream {
    private final ChannelHandlerContext ctx;

    public ChunkedHttpOutputStream(ChannelHandlerContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void write(int b) throws IOException {
        write(new byte[] {(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        ByteBuf data = Unpooled.copiedBuffer(b, off, len);
        DefaultHttpContent msg = new DefaultHttpContent(data);
        ctx.write(msg);
    }

    @Override
    public void flush() throws IOException {
        ctx.flush();
    }

    @Override
    public void close() throws IOException {
        flush();
    }
}
