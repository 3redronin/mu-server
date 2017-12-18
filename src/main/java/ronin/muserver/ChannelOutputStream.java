package ronin.muserver;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpContent;

import java.io.IOException;
import java.io.OutputStream;

class ChannelOutputStream extends OutputStream {
	private ChannelHandlerContext ctx;

	ChannelOutputStream(ChannelHandlerContext ctx) {
		this.ctx = ctx;
	}

	@Override
	public void write(int b) throws IOException {
		write(new byte[] { (byte)b });
	}

	@Override
	public void write(byte[] b) throws IOException {
		ctx.writeAndFlush(new DefaultHttpContent(Unpooled.wrappedBuffer(b)));
	}

	@Override
	public void write(byte[] b, int off, int len) throws IOException {
		ctx.writeAndFlush(new DefaultHttpContent(Unpooled.wrappedBuffer(b, off, len)));
	}

	@Override
	public void flush() throws IOException {
		ctx.flush();
	}

	@Override
	public void close() throws IOException {
		ctx.close();
	}
}
