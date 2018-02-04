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

    OutputStream outputStream(int bufferSizeInBytes);

    PrintWriter writer();

    PrintWriter writer(int bufferSizeInChars);

    void sendFile(File file) throws IOException;

}

class NettyResponseAdaptor implements MuResponse {
    private OutputState outputState = OutputState.NOTHING;
    private boolean chunkResponse = false;
    private final ChannelHandlerContext ctx;
    private final NettyRequestAdapter request;
    private HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK, false);
    private boolean headersWritten = false;
    private final Headers headers = new Headers();
    private boolean keepAlive;
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
        if (headersWritten) {
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

            if (!chunkResponse && !headers.contains(HeaderNames.CONTENT_LENGTH)) {
                response.headers().set(HttpHeaderNames.CONTENT_LENGTH, HttpHeaderValues.ZERO);
            }

            writeHeaders(response, headers);


            ctx.write(response);
        }
    }

    private static void writeHeaders(HttpResponse response, Headers headers) {
        response.headers().add(headers.nettyHeaders());
    }

    public Future<Void> writeAsync(String text) {
        useChunkedMode();
        ensureHeadersWritten();
        return ctx.write(new DefaultHttpContent(textToBuffer(text)));
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
        return outputStream(16 * 1024); // TODO find a good value for this default and make it configurable
    }

    public OutputStream outputStream(int bufferSizeInBytes) {
        useChunkedMode();
        ensureHeadersWritten();
        return new ChunkOutputStream(ctx, bufferSizeInBytes);
    }

    public PrintWriter writer() {
        return writer(16 * 1024); // TODO find a good value for this default and make it configurable
    }

    public PrintWriter writer(int bufferSizeInChars) {
        ensureHeadersWritten();
        return new PrintWriter(new OutputStreamWriter(outputStream(bufferSizeInChars), StandardCharsets.UTF_8));
    }

    @Override
    public void sendFile(File file) throws IOException {
        // https://github.com/netty/netty/blob/4.1/example/src/main/java/io/netty/example/http/file/HttpStaticFileServerHandler.java
        RandomAccessFile raf = new RandomAccessFile(file, "r");

        long fileLength = raf.length();

        response = new DefaultHttpResponse(HTTP_1_1, OK);

        HttpUtil.setContentLength(response, raf.length());
        keepAlive = !headers.contains(HeaderNames.CONNECTION) && request.isKeepAliveRequested();

        if (keepAlive) {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        }


        // Write the initial line and the header.
        ctx.write(response);

        // Write the content.
        ChannelFuture sendFileFuture;
        ChannelFuture lastContentFuture;
        if (ctx.pipeline().get(SslHandler.class) == null) {
            sendFileFuture =
                ctx.write(new DefaultFileRegion(raf.getChannel(), 0, fileLength), ctx.newProgressivePromise());
            // Write the end marker.
            lastContentFuture = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
        } else {
            sendFileFuture =
                ctx.writeAndFlush(new HttpChunkedInput(new ChunkedFile(raf, 0, fileLength, 8192)),
                    ctx.newProgressivePromise());
            // HttpChunkedInput will write the end marker (LastHttpContent) for us.
            lastContentFuture = sendFileFuture;
        }

        sendFileFuture.addListener(new ChannelProgressiveFutureListener() {
            @Override
            public void operationProgressed(ChannelProgressiveFuture future, long progress, long total) {
                if (total < 0) { // total unknown
                    System.err.println(future.channel() + " Transfer progress: " + progress);
                } else {
                    System.err.println(future.channel() + " Transfer progress: " + progress + " / " + total);
                }
            }

            @Override
            public void operationComplete(ChannelProgressiveFuture future) {
                System.err.println(future.channel() + " Transfer complete.");
            }
        });

        // Decide whether to close the connection or not.
        if (!keepAlive) {
            // Close the connection when the whole content is written out.
            lastContentFuture.addListener(ChannelFutureListener.CLOSE);
        }
    }

    public Future<Void> complete() {
        System.out.println("Complete called and status is " + outputState);

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