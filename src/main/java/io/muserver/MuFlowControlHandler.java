package io.muserver;

import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.ReferenceCountUtil;

import java.util.ArrayDeque;
import java.util.Queue;

/**
 * Ensures that at most one decoded message is sent downstream for each read request.
 *
 * <p>This is intentionally local rather than using Netty's {@code FlowControlHandler}. Netty
 * 4.1.136 and 4.2.15+ changed that handler so that an upstream read-complete event can consume an
 * outstanding read without delivering a message. Mu Server requires the outstanding read to stay
 * active until a decoded HTTP message is available.</p>
 */
final class MuFlowControlHandler extends ChannelDuplexHandler {
    private final Queue<Object> queue = new ArrayDeque<>(2);
    private ChannelConfig config;
    private boolean shouldConsume;

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        config = ctx.channel().config();
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        while (!queue.isEmpty()) {
            ctx.fireChannelRead(queue.remove());
        }
        destroy();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        destroy();
        ctx.fireChannelInactive();
    }

    @Override
    public void read(ChannelHandlerContext ctx) {
        if (dequeue(ctx, 1) == 0) {
            shouldConsume = true;
            ctx.read();
        } else if (config.isAutoRead()) {
            ctx.read();
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        queue.offer(msg);
        int minimumToConsume = shouldConsume ? 1 : 0;
        shouldConsume = false;
        dequeue(ctx, minimumToConsume);
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        // An upstream completion can be propagated, but it must not clear shouldConsume: the
        // outstanding downstream read still needs to receive the next decoded message.
        if (queue.isEmpty()) {
            ctx.fireChannelReadComplete();
        }
    }

    private int dequeue(ChannelHandlerContext ctx, int minimumToConsume) {
        int consumed = 0;
        while (consumed < minimumToConsume || config.isAutoRead()) {
            Object msg = queue.poll();
            if (msg == null) {
                break;
            }
            consumed++;
            ctx.fireChannelRead(msg);
        }
        if (consumed > 0 && queue.isEmpty()) {
            ctx.fireChannelReadComplete();
        }
        return consumed;
    }

    private void destroy() {
        Object msg;
        while ((msg = queue.poll()) != null) {
            ReferenceCountUtil.safeRelease(msg);
        }
    }
}
