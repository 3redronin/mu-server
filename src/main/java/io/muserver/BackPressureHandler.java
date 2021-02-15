package io.muserver;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

import java.util.LinkedList;
import java.util.Queue;

class BackPressureHandler extends ChannelDuplexHandler {
    static final String NAME = "pressure";

    private final Queue<Runnable> todo = new LinkedList<>();

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (ctx.channel().isWritable()) {
            super.write(ctx, msg, promise);
        } else {
            ChannelPromise delayed = ctx.newPromise();
            todo.add(() -> delayed.addListener(future -> {
                if (future.isSuccess()) {
                    promise.setSuccess();
                } else {
                    promise.setFailure(future.cause());
                }
            }));
            super.write(ctx, msg, delayed);
        }
    }

    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
        if (!todo.isEmpty()) {
            clearTasks();
        }
        super.channelUnregistered(ctx);
    }
    
    private void clearTasks() {
        Runnable task;
        while ((task = todo.poll()) != null) {
            task.run();
        }
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        if (ctx.channel().isWritable()) {
            clearTasks();
        }
        super.channelWritabilityChanged(ctx);
    }
}
