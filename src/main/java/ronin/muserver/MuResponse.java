package ronin.muserver;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.CharsetUtil;
import ronin.muserver.handlers.HeaderNames;

import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Future;

public interface MuResponse {

	int status();
	void status(int value);

	Future<Void> writeAsync(String text);
	void write(String text);

	void redirect(String url);
	void redirect(URI uri);

	Headers headers();

	OutputStream outputStream();
	OutputStream outputStream(int bufferSizeInBytes);
	PrintWriter writer();
	PrintWriter writer(int bufferSizeInChars);
}

class NettyResponseAdaptor implements MuResponse {
	private final ChannelHandlerContext ctx;
    private final NettyRequestAdapter request;
    private final HttpResponse response;
	private volatile boolean headersWritten = false;
	private final Headers headers = new Headers();

	public NettyResponseAdaptor(ChannelHandlerContext ctx, NettyRequestAdapter request, HttpResponse response) {
		this.ctx = ctx;
        this.request = request;
        this.response = response;
	}

	public int status() {
		return response.status().code();
	}

	public void status(int value) {
		if (headersWritten) {
			throw new IllegalStateException("Cannot set the status after the headers have already been sent");
		}
		response.setStatus(HttpResponseStatus.valueOf(value));

	}

	private void ensureHeadersWritten() {
		if (!headersWritten) {
			headersWritten = true;
			response.headers().add(headers.nettyHeaders());
			ctx.write(response);
		}
	}

	public Future<Void> writeAsync(String text) {
		ensureHeadersWritten();
		return ctx.write(new DefaultHttpContent(Unpooled.copiedBuffer(text, CharsetUtil.UTF_8)));
	}

	public void write(String text) {
		writeAsync(text);
	}

    public void redirect(String newLocation) {
        redirect(URI.create(newLocation));
    }

    public void redirect(URI newLocation) {
        URI absoluteUrl = request.uri().resolve(newLocation);
        status(302);
        headers().add(HeaderNames.LOCATION, absoluteUrl.toString());
    }

	public Headers headers() {
		return headers;
	}

	public OutputStream outputStream() {
		return outputStream(32*1024); // TODO find a good value for this default and make it configurable
	}

	public OutputStream outputStream(int bufferSizeInBytes) {
		ensureHeadersWritten();
		return new BufferedOutputStream(new ChannelOutputStream(ctx), bufferSizeInBytes);
	}

	public PrintWriter writer() {
		return writer(32*1024); // TODO find a good value for this default and make it configurable
	}

	public PrintWriter writer(int bufferSizeInChars) {
		ensureHeadersWritten();
		return new PrintWriter(new OutputStreamWriter(outputStream(bufferSizeInChars), StandardCharsets.UTF_8));
	}

	public Future<Void> complete() {
		ensureHeadersWritten();
		return ctx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
	}
}