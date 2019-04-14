package io.muserver;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;

import java.util.Map;

import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

class NettyResponseAdaptorH1 extends NettyResponseAdaptor {

    private final ChannelHandlerContext ctx;
    private final H1Headers headers;

    NettyResponseAdaptorH1(ChannelHandlerContext ctx, NettyRequestAdapter request, H1Headers headers) {
        super(request, headers, 1);
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
        writeHeaders(response, headers);
        lastAction = ctx.write(response);
    }

    private static void writeHeaders(HttpResponse response, Headers headers) {
        HttpHeaders rh = response.headers();
        for (Map.Entry<String, String> header : headers) {
            rh.add(header.getKey(), header.getValue());
        }
    }


    @Override
    ChannelFuture writeToChannel(boolean isLast, ByteBuf content) {
        HttpContent msg = isLast ? new DefaultLastHttpContent(content) : new DefaultHttpContent(content);
        return ctx.writeAndFlush(msg);
    }


    @Override
    protected void writeFullResponse(ByteBuf body) {
        FullHttpResponse resp = isHead ?
            new EmptyHttpResponse(httpStatus())
            : new DefaultFullHttpResponse(HTTP_1_1, httpStatus(), body, false);
        writeHeaders(resp, this.headers);
        lastAction = ctx.writeAndFlush(resp).syncUninterruptibly();
    }


    @Override
    protected void writeRedirectResponse() {
        HttpResponse resp = new EmptyHttpResponse(httpStatus());
        writeHeaders(resp, this.headers);
        lastAction = ctx.writeAndFlush(resp);
    }

    @Override
    protected void sendEmptyResponse(boolean addContentLengthHeader) {
        HttpResponse msg = isHead ?
            new EmptyHttpResponse(httpStatus()) :
            new DefaultFullHttpResponse(HTTP_1_1, httpStatus(), false);
        writeHeaders(msg, this.headers);
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
