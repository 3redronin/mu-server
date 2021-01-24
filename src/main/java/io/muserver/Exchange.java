package io.muserver;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.timeout.IdleStateEvent;

/**
 * Either a request and response exchange between a client and server, or a websocket session
 */
interface Exchange {

    void onMessage(ChannelHandlerContext ctx, Object message) throws UnexpectedMessageException;

    void onIdleTimeout(ChannelHandlerContext ctx, IdleStateEvent ise);

    void onException(ChannelHandlerContext ctx, Throwable cause);

    void onConnectionEnded(ChannelHandlerContext ctx);

    /**
     * @return The connection that this exchange takes place on
     */
    HttpConnection connection();

}
