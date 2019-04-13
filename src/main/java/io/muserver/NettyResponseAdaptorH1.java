package io.muserver;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;

import java.net.URI;
import java.util.Map;

import static io.muserver.ContentTypes.TEXT_PLAIN_UTF8;
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
        declaredLength = headers.contains(HeaderNames.CONTENT_LENGTH)
            ? Long.parseLong(headers.get(HeaderNames.CONTENT_LENGTH))
            : -1;
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
    ChannelFuture write(ByteBuf data, boolean sync) {
        throwIfFinished();
        int size = data.writerIndex();
        if (size == 0) {
            return lastAction;
        }

        bytesStreamed += size;
        ChannelFuture lastAction;

        if (declaredLength > -1 && bytesStreamed > declaredLength) {
            ctx.channel().close();
            throw new IllegalStateException("The declared content length for " + request + " was " + declaredLength + " bytes. " +
                "The current write is being aborted and the connection is being closed because it would have resulted in " +
                bytesStreamed + " bytes being sent.");
        } else {
            boolean isLast = bytesStreamed == declaredLength;
            if (isLast) {
                outputState = OutputState.FULL_SENT;
            }

            ByteBuf content = Unpooled.wrappedBuffer(data);
            HttpContent msg = isLast ? new DefaultLastHttpContent(content) : new DefaultHttpContent(content);
            lastAction = ctx.writeAndFlush(msg);
        }
        if (sync) {
            // force exception if writes fail
            lastAction = lastAction.syncUninterruptibly();
        }
        this.lastAction = lastAction;
        return lastAction;
    }

    @Override
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

        if (!headers.contains(HeaderNames.CONTENT_TYPE)) {
            headers.set(HeaderNames.CONTENT_TYPE, TEXT_PLAIN_UTF8);
        }

        writeHeaders(resp, this.headers);
        HttpUtil.setContentLength(resp, bodyLength);
        lastAction = ctx.writeAndFlush(resp).syncUninterruptibly();
    }

    public void redirect(URI newLocation) {
        URI absoluteUrl = request.uri().resolve(newLocation);
        if (status < 300 || status > 303) {
            status(302);
        }
        headers().set(HeaderNames.LOCATION, absoluteUrl.toString());
        HttpResponse resp = new EmptyHttpResponse(httpStatus());
        writeHeaders(resp, this.headers);
        HttpUtil.setContentLength(resp, 0);
        lastAction = ctx.writeAndFlush(resp);
        outputState = OutputState.FULL_SENT;
    }

    @Override
    protected void sendEmptyResponse(boolean isFixedLength) {
        HttpResponse msg = isHead ?
            new EmptyHttpResponse(httpStatus()) :
            new DefaultFullHttpResponse(HTTP_1_1, httpStatus(), false);
            writeHeaders(msg, this.headers);
        if ((!isHead || !isFixedLength) && status != 204 && status != 205 && status != 304) {
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
