package io.muserver;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private static final Logger log = LoggerFactory.getLogger(NettyResponseAdaptor.class);
    private final boolean isHead;
    private OutputState outputState = OutputState.NOTHING;
    private final ChannelHandlerContext ctx;
    private final NettyRequestAdapter request;
    private final Headers headers = new Headers();
    private ChannelFuture lastAction;
    private int status = 200;
    private PrintWriter writer;
    private ChunkedHttpOutputStream outputStream;
    private long bytesStreamed = 0;

    private enum OutputState {
        NOTHING, FULL_SENT, STREAMING, STREAMING_COMPLETE
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
        writeHeaders(response, headers);

        // Force chunked on everything due to bug in fixed length (fails on travis-ci only)
        response.headers().remove(HeaderNames.CONTENT_LENGTH);

        if (!response.headers().contains(HeaderNames.CONTENT_LENGTH)) {
            response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
        }

        lastAction = ctx.write(response);
    }

    private static void writeHeaders(HttpResponse response, Headers headers) {
        response.headers().add(headers.nettyHeaders());
    }

    private void throwIfFinished() {
        if (outputState == OutputState.FULL_SENT || outputState == OutputState.STREAMING_COMPLETE) {
            throw new IllegalStateException("Cannot write data as response has already completed");
        }
    }

    public Future<Void> writeAsync(String text) {
        return write(textToBuffer(text), false);
    }

    ChannelFuture write(ByteBuffer data) {
        if (outputState == OutputState.NOTHING) {
            startStreaming();
        }
        return write(Unpooled.wrappedBuffer(data), false);
    }

    ChannelFuture write(ByteBuf data, boolean sync) {
        throwIfFinished();
        int size = data.writerIndex();
        lastAction = ctx.writeAndFlush(new DefaultHttpContent(Unpooled.wrappedBuffer(data)));
        if (sync) {
            lastAction = lastAction.syncUninterruptibly();
        }
        bytesStreamed += size;
        return lastAction;
    }


    public void write(String text) {
        throwIfFinished();
        if (outputState != OutputState.NOTHING) {
            String what = outputState == OutputState.FULL_SENT ? "twice for one response" : "after sending chunks";
            throw new IllegalStateException("You cannot call write " + what + ". If you want to send text in multiple chunks, use sendChunk instead.");
        }
        outputState = OutputState.FULL_SENT;
        ByteBuf body = textToBuffer(text);
        long bodyLength = body.writerIndex();
        FullHttpResponse resp = isHead ?
            new EmptyHttpResponse(httpStatus())
            : new DefaultFullHttpResponse(HTTP_1_1, httpStatus(), body, false);

        writeHeaders(resp, this.headers);
        HttpUtil.setContentLength(resp, bodyLength);
        lastAction = ctx.writeAndFlush(resp).syncUninterruptibly();
    }

    public void sendChunk(String text) {
        if (outputState == OutputState.NOTHING) {
            startStreaming();
        }
        lastAction = write(textToBuffer(text), true);
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
        writeHeaders(resp, this.headers);
        HttpUtil.setContentLength(resp, 0);
        lastAction = ctx.writeAndFlush(resp).syncUninterruptibly();
        outputState = OutputState.FULL_SENT;
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
            this.outputStream = new ChunkedHttpOutputStream(this);
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

    ChannelFuture complete(boolean forceDisconnect) {
        try {

            boolean shouldDisconnect = forceDisconnect || !request.isKeepAliveRequested();
            if (ctx.channel().isActive()) {
                if (outputState == OutputState.NOTHING) {
                    HttpResponse msg = isHead ?
                        new EmptyHttpResponse(httpStatus()) :
                        new DefaultFullHttpResponse(HTTP_1_1, httpStatus(), false);
                    msg.headers().add(this.headers.nettyHeaders());
                    if (!isHead || !(headers().contains(HeaderNames.CONTENT_LENGTH))) {
                        msg.headers().set(HeaderNames.CONTENT_LENGTH, 0);
                    }
                    msg.headers().set(HeaderNames.CONNECTION, HeaderValues.CLOSE);
                    shouldDisconnect |= writeAndFlushSafely(msg);
                } else if (outputState == OutputState.STREAMING) {
                    if (isHead) {
                        ctx.channel().flush();
                    } else {
                        if (writer != null) {
                            writer.close();
                        }
                        if (outputStream != null) {
                            outputStream.close();
                        }
                        outputState = OutputState.STREAMING_COMPLETE;
                        shouldDisconnect |= writeAndFlushSafely(LastHttpContent.EMPTY_LAST_CONTENT);
                    }
                }

                if (!isHead && (headers().contains(HeaderNames.CONTENT_LENGTH))) {
                    long declaredLength = Long.parseLong(headers.get(HeaderNames.CONTENT_LENGTH));
                    long actualLength = this.bytesStreamed;
                    if (declaredLength != actualLength) {
                        shouldDisconnect = true;
                        log.warn("Declared length " + declaredLength + " doesn't equal actual length " + actualLength + " for " + request);
                    }
                }

            }

            if (shouldDisconnect) {
                if (lastAction == null || !ctx.channel().isActive()) {
                    lastAction = ctx.channel().close();
                } else {
                    lastAction = lastAction.addListener(ChannelFutureListener.CLOSE);
                }
            }
            return lastAction;
        } catch (Exception e) {
            log.error("Unexpected exception during complete", e);
            ctx.channel().close();
            throw e;
        }
    }

    private boolean writeAndFlushSafely(HttpObject msg) {
        try {
            lastAction = ctx.channel().writeAndFlush(msg).syncUninterruptibly();
            return false;
        } catch (Exception e) {
            log.info("Error while sending last content", e);
            log.error("Stack trace", new MuException("stack trace"));
            return true;
        }
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
