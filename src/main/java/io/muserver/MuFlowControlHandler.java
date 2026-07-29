package io.muserver;

import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.ObjectPool;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

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
class MuFlowControlHandler extends ChannelDuplexHandler {
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(MuFlowControlHandler.class);

    private final boolean releaseMessages;

    private MuFlowControlHandler.RecyclableArrayDeque queue;

    private ChannelConfig config;

    private boolean shouldConsume;

    public MuFlowControlHandler() {
        this(true);
    }

    public MuFlowControlHandler(boolean releaseMessages) {
        this.releaseMessages = releaseMessages;
    }

    /**
     * Determine if the underlying {@link Queue} is empty. This method exists for
     * testing, debugging and inspection purposes and it is not Thread safe!
     */
    boolean isQueueEmpty() {
        return queue == null || queue.isEmpty();
    }

    /**
     * Releases all messages and destroys the {@link Queue}.
     */
    private void destroy() {
        if (queue != null) {

            if (!queue.isEmpty()) {
                logger.trace("Non-empty queue: {}", queue);

                if (releaseMessages) {
                    Object msg;
                    while ((msg = queue.poll()) != null) {
                        ReferenceCountUtil.safeRelease(msg);
                    }
                }
            }

            queue.recycle();
            queue = null;
        }
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        config = ctx.channel().config();
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        super.handlerRemoved(ctx);
        if (!isQueueEmpty()) {
            dequeue(ctx, queue.size());
        }
        destroy();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        destroy();
        ctx.fireChannelInactive();
    }

    @Override
    public void read(ChannelHandlerContext ctx) throws Exception {
        if (dequeue(ctx, 1) == 0) {
            // It seems no messages were consumed. We need to read() some
            // messages from upstream and once one arrives it need to be
            // relayed to downstream to keep the flow going.
            shouldConsume = true;
            ctx.read();
        } else if (config.isAutoRead()) {
            ctx.read();
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (queue == null) {
            queue = MuFlowControlHandler.RecyclableArrayDeque.newInstance();
        }

        queue.offer(msg);

        // We just received one message. Do we need to relay it regardless
        // of the auto reading configuration? The answer is yes if this
        // method was called as a result of a prior read() call.
        int minConsume = shouldConsume ? 1 : 0;
        shouldConsume = false;

        dequeue(ctx, minConsume);
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        if (isQueueEmpty()) {
            ctx.fireChannelReadComplete();
        } else {
            // Don't relay completion events from upstream as they
            // make no sense in this context. See dequeue() where
            // a new set of completion events is being produced.
        }
    }

    /**
     * Dequeues one or many (or none) messages depending on the channel's auto
     * reading state and returns the number of messages that were consumed from
     * the internal queue.
     *
     * The {@code minConsume} argument is used to force {@code dequeue()} into
     * consuming that number of messages regardless of the channel's auto
     * reading configuration.
     *
     * @see #read(ChannelHandlerContext)
     * @see #channelRead(ChannelHandlerContext, Object)
     */
    private int dequeue(ChannelHandlerContext ctx, int minConsume) {
        int consumed = 0;

        // fireChannelRead(...) may call ctx.read() and so this method may reentrance. Because of this we need to
        // check if queue was set to null in the meantime and if so break the loop.
        while (queue != null && (consumed < minConsume || config.isAutoRead())) {
            Object msg = queue.poll();
            if (msg == null) {
                break;
            }

            ++consumed;
            ctx.fireChannelRead(msg);
        }

        // We're firing a completion event every time one (or more)
        // messages were consumed and the queue ended up being drained
        // to an empty state.
        if (queue != null && queue.isEmpty()) {
            queue.recycle();
            queue = null;

            if (consumed > 0) {
                ctx.fireChannelReadComplete();
            }
        }

        return consumed;
    }

    /**
     * A recyclable {@link ArrayDeque}.
     */
    private static final class RecyclableArrayDeque extends ArrayDeque<Object> {

        private static final long serialVersionUID = 0L;

        /**
         * A value of {@code 2} should be a good choice for most scenarios.
         */
        private static final int DEFAULT_NUM_ELEMENTS = 2;

        private static final ObjectPool<MuFlowControlHandler.RecyclableArrayDeque> RECYCLER = ObjectPool.newPool(
            new ObjectPool.ObjectCreator<MuFlowControlHandler.RecyclableArrayDeque>() {
                @Override
                public MuFlowControlHandler.RecyclableArrayDeque newObject(ObjectPool.Handle<MuFlowControlHandler.RecyclableArrayDeque> handle) {
                    return new MuFlowControlHandler.RecyclableArrayDeque(DEFAULT_NUM_ELEMENTS, handle);
                }
            });

        public static MuFlowControlHandler.RecyclableArrayDeque newInstance() {
            return RECYCLER.get();
        }

        private final ObjectPool.Handle<MuFlowControlHandler.RecyclableArrayDeque> handle;

        private RecyclableArrayDeque(int numElements, ObjectPool.Handle<MuFlowControlHandler.RecyclableArrayDeque> handle) {
            super(numElements);
            this.handle = handle;
        }

        public void recycle() {
            clear();
            handle.recycle(this);
        }
    }
}
