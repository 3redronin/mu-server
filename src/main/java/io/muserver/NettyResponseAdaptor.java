package io.muserver;

import io.netty.buffer.ByteBuf;
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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.concurrent.Future;

import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

class NettyResponseAdaptor implements MuResponse {
    private final boolean isHead;
    private OutputState outputState = OutputState.NOTHING;
    private final ChannelHandlerContext ctx;
    private final NettyRequestAdapter request;
    private final Headers headers = new Headers();
    private ChannelFuture lastAction;
    private int status = 200;
    private PrintWriter writer;
    private ChunkedHttpOutputStream outputStream;

    private enum OutputState {
        NOTHING, FULL_SENT, STREAMING
    }

    NettyResponseAdaptor(ChannelHandlerContext ctx, NettyRequestAdapter request) {
        this.ctx = ctx;
        this.request = request;
        this.isHead = request.method() == Method.HEAD;

        headers.set(HeaderNames.DATE, Mutils.toHttpDate(new Date()));
    }

    public int status() {
        return status;
    }

    public void status(int value) {
        if (outputState != OutputState.NOTHING) {
            throw new IllegalStateException("Cannot set the status after the headers have already been sent");
        }
        status = value;
    }

    private void startStreaming() {
        if (outputState != OutputState.NOTHING) {
            throw new IllegalStateException("Cannot start streaming when state is " + outputState);
        }
        outputState = OutputState.STREAMING;
        HttpResponse response = isHead ? new EmptyHttpResponse(httpStatus()) : new DefaultHttpResponse(HTTP_1_1, httpStatus(), false);
        writeHeaders(response, headers, request);
        if (!response.headers().contains(HeaderNames.CONTENT_LENGTH)) {
            response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
        }
        lastAction = ctx.writeAndFlush(response);
    }

    private static void writeHeaders(HttpResponse response, Headers headers, NettyRequestAdapter request) {
        response.headers().add(headers.nettyHeaders());
        if (request.isKeepAliveRequested()) {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        }
    }

    public Future<Void> writeAsync(String text) {
        if (outputState == OutputState.NOTHING) {
            startStreaming();
        }
        lastAction = ctx.writeAndFlush(new DefaultHttpContent(textToBuffer(text)));
        return lastAction;
    }

    ChannelFuture write(ByteBuffer data) {
        if (outputState == OutputState.NOTHING) {
            startStreaming();
        }
        lastAction = ctx.writeAndFlush(new DefaultHttpContent(Unpooled.wrappedBuffer(data)));
        return lastAction;
    }


    public void write(String text) {
        if (outputState != OutputState.NOTHING) {
            String what = outputState == OutputState.FULL_SENT ? "twice for one response" : "after sending chunks";
            throw new IllegalStateException("You cannot call write " + what + ". If you want to send text in multiple chunks, use sendChunk instead.");
        }
        outputState = OutputState.FULL_SENT;
        FullHttpResponse resp = isHead ?
            new EmptyHttpResponse(httpStatus())
            : new DefaultFullHttpResponse(HTTP_1_1, httpStatus(), textToBuffer(text), false);

        writeHeaders(resp, this.headers, request);
        HttpUtil.setContentLength(resp, text.length());
        lastAction = ctx.writeAndFlush(resp).syncUninterruptibly();
    }

    public void sendChunk(String text) {
        if (outputState == OutputState.NOTHING) {
            startStreaming();
        }
        lastAction = ctx.writeAndFlush(new DefaultHttpContent(textToBuffer(text))).syncUninterruptibly();
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
        HttpResponse resp = new EmptyHttpResponse(httpStatus());
        writeHeaders(resp, this.headers, request);
        HttpUtil.setContentLength(resp, 0);
        lastAction = ctx.writeAndFlush(resp);
    }

    public Headers headers() {
        return headers;
    }

    public void contentType(CharSequence contentType) {
        headers.set(HeaderNames.CONTENT_TYPE, contentType);
    }

    public void addCookie(Cookie cookie) {
        headers.add(HeaderNames.SET_COOKIE, ServerCookieEncoder.STRICT.encode(cookie.nettyCookie));
    }

    public OutputStream outputStream() {
        if (this.outputStream == null) {
            startStreaming();
            this.outputStream = new ChunkedHttpOutputStream(ctx);
        }
        return this.outputStream;
    }

    public PrintWriter writer() {
        if (this.writer == null) {
            OutputStreamWriter os = new OutputStreamWriter(outputStream(), StandardCharsets.UTF_8);
            this.writer = new PrintWriter(os);
        }
        return this.writer;
    }

    @Override
    public boolean hasStartedSendingData() {
        return outputState != OutputState.NOTHING;
    }

    Future<Void> complete() {
        if (outputState == OutputState.NOTHING) {
            HttpResponse msg = isHead ?
                new EmptyHttpResponse(httpStatus()) :
                new DefaultFullHttpResponse(HTTP_1_1, httpStatus(), false);
            msg.headers().add(this.headers.nettyHeaders());
            if (request.method() != Method.HEAD || !(msg.headers().contains(HeaderNames.CONTENT_LENGTH))) {
                msg.headers().set(HeaderNames.CONTENT_LENGTH, 0);
            }
            lastAction = ctx.writeAndFlush(msg);
        } else if (outputState == OutputState.STREAMING && !isHead) {
            if (writer != null) {
                writer.close();
            }
            lastAction = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
        }
        if (!request.isKeepAliveRequested()) {
            lastAction = lastAction.addListener(ChannelFutureListener.CLOSE);
        }
        return lastAction;
    }

    private HttpResponseStatus httpStatus() {
        return HttpResponseStatus.valueOf(status());
    }

    static class EmptyHttpResponse extends DefaultFullHttpResponse {
        EmptyHttpResponse(HttpResponseStatus status) {
            super(HttpVersion.HTTP_1_1, status, false);
        }
    }

}
