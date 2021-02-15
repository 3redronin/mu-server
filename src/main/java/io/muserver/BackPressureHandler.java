package io.muserver;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

import java.util.LinkedList;
import java.util.Queue;

class BackPressureHandler extends ChannelDuplexHandler {
    static final String NAME = "pressure";

    private final Queue<Delivery> toSend = new LinkedList<>();

    private static class Delivery {
        private final Object msg;
        private final ChannelPromise promise;

        private Delivery(Object msg, ChannelPromise promise) {
            this.msg = msg;
            this.promise = promise;
        }

        public void cancel(Throwable cause) {
            promise.setFailure(cause);
        }

        public void send(ChannelHandlerContext ctx) {
            ctx.write(msg, promise);
        }
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (ctx.channel().isWritable()) {
            super.write(ctx, msg, promise);
        } else {
            toSend.add(new Delivery(msg, promise));
        }
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        super.handlerRemoved(ctx);
        deliverTasks(ctx);
    }


    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        super.channelInactive(ctx);
        deliverTasks(ctx); // even though we know these will fail, by delivering them it gives relevant handles to release buffers
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        super.channelWritabilityChanged(ctx);
        deliverTasks(ctx);
    }

    private void deliverTasks(ChannelHandlerContext ctx) {
        Delivery task;
        while (ctx.channel().isWritable() && (task = toSend.poll()) != null) {
            task.send(ctx);
        }
    }
}
