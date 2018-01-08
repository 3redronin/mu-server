package io.muserver;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.netty.util.CharsetUtil;

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
	void contentType(CharSequence contentType);
	void addCookie(io.muserver.Cookie cookie);

	OutputStream outputStream();
	OutputStream outputStream(int bufferSizeInBytes);
	PrintWriter writer();
	PrintWriter writer(int bufferSizeInChars);
}

class NettyResponseAdaptor implements MuResponse {
    private boolean chunkResponse = false;
	private final ChannelHandlerContext ctx;
    private final NettyRequestAdapter request;
    private final HttpResponse response;
	private boolean headersWritten = false;
	private final Headers headers = new Headers();
	private boolean keepAlive;

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

	private void useChunkedMode() {
	    if (!chunkResponse) {
	        chunkResponse = true;
            response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
            response.headers().remove(HttpHeaderNames.CONTENT_LENGTH);
        }
    }

	private void ensureHeadersWritten() {
		if (!headersWritten) {
			headersWritten = true;

			keepAlive = !headers.contains(HeaderNames.CONNECTION) && request.isKeepAliveRequested();
            if (keepAlive) {
                response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            }

            if (!chunkResponse) {
                response.headers().set(HttpHeaderNames.CONTENT_LENGTH, HttpHeaderValues.ZERO);
            }

			response.headers().add(headers.nettyHeaders());


			ctx.write(response);
		}
	}

	public Future<Void> writeAsync(String text) {
	    useChunkedMode();
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

    public void contentType(CharSequence contentType) {
        headers.set(HeaderNames.CONTENT_TYPE, contentType);
    }

    public void addCookie(io.muserver.Cookie cookie) {
        headers.add(HeaderNames.SET_COOKIE, ServerCookieEncoder.STRICT.encode(cookie.nettyCookie));
    }

    public OutputStream outputStream() {
		return outputStream(16*1024); // TODO find a good value for this default and make it configurable
	}

	public OutputStream outputStream(int bufferSizeInBytes) {
	    useChunkedMode();
		ensureHeadersWritten();
		return new ChunkOutputStream(ctx, bufferSizeInBytes);
	}

	public PrintWriter writer() {
		return writer(16*1024); // TODO find a good value for this default and make it configurable
	}

	public PrintWriter writer(int bufferSizeInChars) {
		ensureHeadersWritten();
		return new PrintWriter(new OutputStreamWriter(outputStream(bufferSizeInChars), StandardCharsets.UTF_8));
	}

	public Future<Void> complete() {
		ensureHeadersWritten();
        ChannelFuture completeFuture = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
        if (!keepAlive) {
            completeFuture = completeFuture.addListener(ChannelFutureListener.CLOSE);
        }
        return completeFuture;
	}
}