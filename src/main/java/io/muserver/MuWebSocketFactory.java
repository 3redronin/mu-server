package io.muserver;

/**
 * <p>A factory that can convert an upgrade request into a web socket.</p>
 * <p>This is registed with the {@link WebSocketHandlerBuilder#withWebSocketFactory(MuWebSocketFactory)} method.</p>
 */
public interface MuWebSocketFactory {

    /**
     * Creates a web socket for an upgrade request.
     * @param request An upgrade request.
     * @param responseHeaders Any headers added to this object will be returned with the upgrade response.
     * @return A web socket, or null if no websocket should be created (in which case the next handler in the chain
     * will be called).
     * @throws Exception Any thrown exceptions will result in errors being returned to the client. Note that
     *                   exceptions such as {@link javax.ws.rs.ClientErrorException} can be used in order to
     *                   control the HTTP response codes.
     */
    MuWebSocket create(MuRequest request, Headers responseHeaders) throws Exception;

}
