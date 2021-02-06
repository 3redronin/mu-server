package io.muserver;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This pre-emptively reads a message from the channel before the HTTP handler has actually asked for it.
 * The reason for this is to force more time waiting on a read at the NIO channel, and therefore detect
 * client disconnections. If there is a better way, it may be revealed here: https://stackoverflow.com/questions/66075288/detecting-closed-channels-when-auto-read-is-false-in-netty-4
 */
class PreReader extends ChannelDuplexHandler {

    private Object pendingMsg;
    private boolean wantsToRead = false;

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        ctx.channel().config().setAutoRead(false);
        ctx.read();
        super.handlerAdded(ctx);
    }

    private void sendItMaybe(ChannelHandlerContext ctx) {
        if (wantsToRead) {
            Object msg = this.pendingMsg;
            if (msg != null) {
                this.pendingMsg = null;
                this.wantsToRead = false;
                ctx.fireChannelRead(msg);
                ReferenceCountUtil.release(msg);
                ctx.read();
            }
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        // A message is available. Set it as pending but only forward to the mu handler if a read
        // has been requested.
        ReferenceCountUtil.retain(msg);
        pendingMsg = msg;
        sendItMaybe(ctx);
    }

    @Override
    public void read(ChannelHandlerContext ctx) throws Exception {
        // The mu handler wants to read. Send it the pending message if it's there; otherwise wait
        wantsToRead = true;
        sendItMaybe(ctx);
    }

    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
        log.info("Cleaning up");
        cleanUp();
        super.channelUnregistered(ctx);
    }

    private static final Logger log = LoggerFactory.getLogger(PreReader.class);


    private void cleanUp() {
        if (pendingMsg != null) {
            ReferenceCountUtil.release(pendingMsg);
            pendingMsg = null;
        }
    }
}
