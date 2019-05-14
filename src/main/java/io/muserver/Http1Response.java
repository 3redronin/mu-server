package io.muserver;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

class Http1Response extends NettyResponseAdaptor {
    private static final Logger log = LoggerFactory.getLogger(Http1Response.class);

    private final ChannelHandlerContext ctx;
    private final Http1Headers headers;

    Http1Response(ChannelHandlerContext ctx, NettyRequestAdapter request, Http1Headers headers) {
        super(request, headers);
        this.ctx = ctx;
        this.headers = headers;
    }

    @Override
    protected void startStreaming() {
        super.startStreaming();
        HttpResponse response = isHead ? new EmptyHttpResponse(httpStatus()) : new DefaultHttpResponse(HTTP_1_1, httpStatus(), false);
        if (declaredLength == -1) {
            headers.set(HeaderNames.TRANSFER_ENCODING, HeaderValues.CHUNKED);
        }
        writeHeaders(response);
        lastAction = ctx.write(response);
    }

    @Override
    protected void onContentLengthMismatch() {
        closeConnection();
        throw new IllegalStateException("The declared content length for " + request + " was " + declaredLength + " bytes. " +
            "The current write is being aborted and the connection is being closed because it would have resulted in " +
            bytesStreamed + " bytes being sent.");
    }

    private void writeHeaders(HttpResponse response) {
        addVaryHeader();
        HttpHeaders rh = response.headers();
        for (Map.Entry<String, String> header : this.headers) {
            rh.add(header.getKey(), header.getValue());
        }
    }


    @Override
    ChannelFuture writeToChannel(boolean isLast, ByteBuf content) {
        HttpContent msg = isLast ? new DefaultLastHttpContent(content) : new DefaultHttpContent(content);
        return ctx.writeAndFlush(msg);
    }

    @Override
    protected boolean onBadRequestSent() {
        if (connectionOpen()) {
            log.warn("Closing client connection for " + request + " because " + declaredLength + " bytes was the " +
                "expected length, however " + bytesStreamed + " bytes were sent.");
        }
        return true;
    }


    @Override
    protected void writeFullResponse(ByteBuf body) {
        FullHttpResponse resp = isHead ?
            new EmptyHttpResponse(httpStatus())
            : new DefaultFullHttpResponse(HTTP_1_1, httpStatus(), body, false);
        writeHeaders(resp);
        lastAction = ctx.writeAndFlush(resp).syncUninterruptibly();
    }


    @Override
    protected void writeRedirectResponse() {
        HttpResponse resp = new EmptyHttpResponse(httpStatus());
        writeHeaders(resp);
        lastAction = ctx.writeAndFlush(resp);
    }

    @Override
    protected void sendEmptyResponse(boolean addContentLengthHeader) {
        HttpResponse msg = isHead ?
            new EmptyHttpResponse(httpStatus()) :
            new DefaultFullHttpResponse(HTTP_1_1, httpStatus(), false);
        writeHeaders(msg);
        if (addContentLengthHeader) {
            msg.headers().set(HeaderNames.CONTENT_LENGTH, 0);
        }
        lastAction = ctx.writeAndFlush(msg);
    }

    @Override
    protected ChannelFuture writeLastContentMarker() {
        return ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
    }


    @Override
    protected boolean connectionOpen() {
        return ctx.channel().isOpen();
    }

    @Override
    protected ChannelFuture closeConnection() {
        return ctx.channel().close();
    }


}
