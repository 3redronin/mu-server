package ronin.muserver;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpContent;

import java.io.OutputStream;

class ChannelOutputStream extends OutputStream {
	private ChannelHandlerContext ctx;

	ChannelOutputStream(ChannelHandlerContext ctx) {
		this.ctx = ctx;
	}

	public void write(int b) {
		write(new byte[] { (byte)b });
	}

	public void write(byte[] b) {
		ctx.writeAndFlush(new DefaultHttpContent(Unpooled.wrappedBuffer(b)));
	}

	public void write(byte[] b, int off, int len) {
		ctx.writeAndFlush(new DefaultHttpContent(Unpooled.wrappedBuffer(b, off, len)));
	}

	public void flush() {
		ctx.flush();
	}

	public void close() {
		ctx.close();
	}
}
