package io.muserver;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.*;

import java.util.Map;

import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

class Http1Response extends NettyResponseAdaptor {

    final ChannelHandlerContext ctx;
    private final Http1Headers headers;

    Http1Response(ChannelHandlerContext ctx, NettyRequestAdapter request, Http1Headers headers) {
        super(request, headers);
        this.ctx = ctx;
        this.headers = headers;
    }

    @Override
    protected ChannelFuture startStreaming() {
        super.startStreaming();
        HttpResponse response = isHead ? new EmptyHttpResponse(httpStatus()) : new DefaultHttpResponse(HTTP_1_1, httpStatus(), false);
        if (declaredLength == -1) {
            headers.set(HeaderNames.TRANSFER_ENCODING, HeaderValues.CHUNKED);
        }
        writeHeaders(response);
        return ctx.write(response);
    }

    @Override
    protected void onContentLengthMismatch() {
        throw new IllegalStateException("The declared content length for " + request + " was " + declaredLength + " bytes. " +
            "The current write is being aborted and the connection is being closed because it would have resulted in " +
            bytesStreamed + " bytes being sent.");
    }

    private void writeHeaders(HttpResponse response) {
        HttpHeaders rh = response.headers();
        for (Map.Entry<String, String> header : this.headers) {
            rh.add(header.getKey(), header.getValue());
        }
    }


    @Override
    ChannelFuture writeAndFlushToChannel(boolean isLast, ByteBuf content) {
        HttpContent msg = isLast ? new DefaultLastHttpContent(content) : new DefaultHttpContent(content);
        return ctx.writeAndFlush(msg);
    }

    @Override
    protected ChannelFuture writeFullResponse(ByteBuf body) {
        if (!ctx.executor().inEventLoop()) {
            ChannelPromise promise = ctx.newPromise();
            ctx.executor().submit(() -> {
                writeFullResponse(body).addListener(future -> {
                    if (future.isSuccess()) {
                        promise.setSuccess();
                    } else {
                        promise.setFailure(future.cause());
                    }
                });
            });
            return promise;
        }

        FullHttpResponse resp = isHead ?
            new EmptyHttpResponse(httpStatus())
            : new DefaultFullHttpResponse(HTTP_1_1, httpStatus(), body, false);
        writeHeaders(resp);
        return ctx.writeAndFlush(resp);
    }

    @Override
    protected ChannelFuture sendEmptyResponse(boolean addContentLengthHeader) {
        HttpResponse msg = isHead ?
            new EmptyHttpResponse(httpStatus()) :
            new DefaultFullHttpResponse(HTTP_1_1, httpStatus(), false);
        writeHeaders(msg);
        if (addContentLengthHeader) {
            msg.headers().set(HeaderNames.CONTENT_LENGTH, HeaderValues.ZERO);
        }
        return ctx.writeAndFlush(msg);
    }

    @Override
    protected ChannelFuture writeLastContentMarker() {
        return ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
    }

    @Override
    public String toString() {
        return "Http1Response{" +
            "outputState=" + outputState() +
            ", status=" + status +
            "}";
    }

    @Override
    public HttpStatus statusValue() {
        return null;
    }

    @Override
    public void status(HttpStatus value) {

    }

    @Override
    public void sendInformationalResponse(HttpStatus status, Headers headers) {

    }

    @Override
    public void addCompletionListener(ResponseCompleteListener listener) {

    }
}
