package io.muserver;

import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.stream.ChunkedFile;
import io.netty.handler.stream.ChunkedNioFile;
import io.netty.handler.stream.ChunkedStream;
import io.netty.util.CharsetUtil;
import sun.net.www.http.ChunkedInputStream;

import java.io.*;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Future;

import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

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

    void sendFile(File file) throws IOException;

}

class NettyResponseAdaptor implements MuResponse {
    private boolean chunkResponse = false;
    private final ChannelHandlerContext ctx;
    private final NettyRequestAdapter request;
    private HttpResponse response;
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

            if (!chunkResponse && !headers.contains(HeaderNames.CONTENT_LENGTH)) {
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
        ensureHeadersWritten();
        ChannelFuture completeFuture = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
        if (!keepAlive) {
            completeFuture = completeFuture.addListener(ChannelFutureListener.CLOSE);
        }
        return completeFuture;
    }
}