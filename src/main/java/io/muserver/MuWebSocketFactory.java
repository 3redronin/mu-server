package io.muserver;

import org.jspecify.annotations.Nullable;

/**
 * <p>A factory that can convert an upgrade request into a web socket.</p>
 * <p>This is registered with the {@link WebSocketHandlerBuilder#withWebSocketFactory(MuWebSocketFactory)} method.</p>
 */
public interface MuWebSocketFactory {

    /**
     * Creates a web socket for an upgrade request, or decides that this request should not be upgraded by this handler.
     *
     * @param request An upgrade request.
     * @param responseHeaders Any headers added to this object will be returned with the upgrade response. One use
     *                        case is selecting one of the values from the request <code>Sec-WebSocket-Protocol</code>
     *                        header and setting the selected sub-protocol in the <code>Sec-WebSocket-Protocol</code>
     *                        response header.
     * @return A web socket, or null if no websocket should be created (in which case the next handler in the chain
     * will be called).
     * @throws Exception Any thrown exceptions will result in errors being returned to the client. Note that
     *                   exceptions such as {@link jakarta.ws.rs.ClientErrorException} can be used in order to
     *                   control the HTTP response codes.
     */
    @Nullable MuWebSocket create(MuRequest request, Headers responseHeaders) throws Exception;

}
