package ronin.muserver;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.CharsetUtil;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

class NettyResponseAdaptor implements MuResponse {
    private final ChannelHandlerContext ctx;
    private final HttpResponse response;
    private volatile boolean headersWritten = false;

    public NettyResponseAdaptor(ChannelHandlerContext ctx, HttpResponse response) {
        this.ctx = ctx;
        this.response = response;
    }

    @Override
    public int status() {
        return response.status().code();
    }

    @Override
    public void status(int value) {
        if (headersWritten) {
            throw new IllegalStateException("Cannot set the status after the headers have already been sent");
        }
        response.setStatus(HttpResponseStatus.valueOf(value));
    }

    private ChannelFuture writeResponseHeaders() {
        headersWritten = true;
        return ctx.write(response);
    }

    @Override
    public Future<Void> writeAsync(String text) {
        if (!headersWritten) {
            writeResponseHeaders();
        }
        return ctx.write(new DefaultHttpContent(Unpooled.copiedBuffer(text, CharsetUtil.UTF_8)));
    }

    @Override
    public void write(String text) {
        writeAsync(text);
    }

    public Future<Void> complete() {
        if (!headersWritten) {
            writeResponseHeaders();
        }
        return ctx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
    }
}
