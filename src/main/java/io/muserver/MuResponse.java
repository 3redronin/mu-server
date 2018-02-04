package io.muserver;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.stream.ChunkedFile;
import io.netty.util.CharsetUtil;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Future;

import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

public interface MuResponse {

    int status();

    void status(int value);

    Future<Void> writeAsync(String text);

    void write(String text);

    void sendChunk(String text);

    void redirect(String url);

    void redirect(URI uri);

    Headers headers();

    void contentType(CharSequence contentType);

    void addCookie(io.muserver.Cookie cookie);

    OutputStream outputStream();
    PrintWriter writer();

}

class NettyResponseAdaptor implements MuResponse {
    private OutputState outputState = OutputState.NOTHING;
    private final ChannelHandlerContext ctx;
    private final NettyRequestAdapter request;
    private HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK, false);
    private final Headers headers = new Headers();
    private ChannelFuture lastAction;

    private enum OutputState {
        NOTHING, FULL_SENT, CHUNKING
    }

    public NettyResponseAdaptor(ChannelHandlerContext ctx, NettyRequestAdapter request) {
        this.ctx = ctx;
        this.request = request;
    }

    public int status() {
        return response.status().code();
    }

    public void status(int value) {
        if (outputState != OutputState.NOTHING) {
            throw new IllegalStateException("Cannot set the status after the headers have already been sent");
        }
        response.setStatus(HttpResponseStatus.valueOf(value));

    }

    private void startChunking() {
        if (outputState != OutputState.NOTHING) {
            throw new IllegalStateException("Cannot start chunking when state is " + outputState);
        }
        outputState = OutputState.CHUNKING;
        response = new DefaultHttpResponse(HTTP_1_1, HttpResponseStatus.valueOf(status()), false);

        response.headers().add(this.headers.nettyHeaders());

        boolean useKeepAlive = request.isKeepAliveRequested();
        if (useKeepAlive) {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        }

        response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
        response.headers().remove(HttpHeaderNames.CONTENT_LENGTH);

        ctx.writeAndFlush(response);
    }

    public Future<Void> writeAsync(String text) {
        sendChunk(text);
        return lastAction;
    }


    public void write(String text) {
        if (outputState != OutputState.NOTHING) {
            String what = outputState == OutputState.FULL_SENT ? "twice for one response" : "after sending chunks";
            throw new IllegalStateException("You cannot call write " + what + ". If you want to send text in multiple chunks, use sendChunk instead.");
        }
        outputState = OutputState.FULL_SENT;
        FullHttpResponse resp = new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.valueOf(this.status()), textToBuffer(text), false);

        resp.headers().add(this.headers.nettyHeaders());
        HttpUtil.setContentLength(resp, text.length());

        if (request.isKeepAliveRequested()) {
            resp.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        }

        lastAction = ctx.writeAndFlush(resp);
    }

    public void sendChunk(String text) {
        if (outputState == OutputState.NOTHING) {
            startChunking();
        }
        lastAction = ctx.writeAndFlush(new DefaultHttpContent(textToBuffer(text)));
    }

    private static ByteBuf textToBuffer(String text) {
        return Unpooled.copiedBuffer(text, CharsetUtil.UTF_8);
    }

    public void redirect(String newLocation) {
        redirect(URI.create(newLocation));
    }

    public void redirect(URI newLocation) {
        URI absoluteUrl = request.uri().resolve(newLocation);
        status(302);
        headers().set(HeaderNames.LOCATION, absoluteUrl.toString());
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
        startChunking();
        return new ChunkedHttpOutputStream(ctx);
    }

    public PrintWriter writer() {
        return new PrintWriter(new OutputStreamWriter(outputStream(), StandardCharsets.UTF_8));
    }

    public Future<Void> complete() {
        if (outputState == OutputState.NOTHING) {
            DefaultFullHttpResponse msg = new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.valueOf(status()), false);
            msg.headers().add(this.headers.nettyHeaders());
            msg.headers().set(HeaderNames.CONTENT_LENGTH, 0);
            lastAction = ctx.writeAndFlush(msg);
        } else if (outputState == OutputState.CHUNKING) {
            lastAction = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
        }
        if (!request.isKeepAliveRequested()) {
            lastAction = lastAction.addListener(ChannelFutureListener.CLOSE);
        }
        return lastAction;
    }
}