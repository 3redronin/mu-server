package io.muserver;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;

/**
 * A handler that can establish a web socket based on web socket upgrade requests.
 * Create with {@link WebSocketHandlerBuilder#webSocketHandler()}
 */
public class WebSocketHandler implements MuHandler {

    private final MuWebSocketFactory factory;
    private final String path;
    private final long idleReadTimeoutMills;
    private final long pingAfterWriteMillis;
    private final int maxFramePayloadLength;

    WebSocketHandler(MuWebSocketFactory factory, String path, long idleReadTimeoutMills, long pingAfterWriteMillis, int maxFramePayloadLength) {
        this.factory = factory;
        this.path = path;
        this.idleReadTimeoutMills = idleReadTimeoutMills;
        this.pingAfterWriteMillis = pingAfterWriteMillis;
        this.maxFramePayloadLength = maxFramePayloadLength;
    }

    @Override
    public boolean handle(MuRequest request, MuResponse response) throws Exception {
        if (request.method() != Method.GET) {
            return false;
        }
        if (Mutils.hasValue(path) && !path.equals(request.relativePath())) {
            return false;
        }

        if (!isWebSocketUpgrade(request)) {
            return false;
        }
        HttpHeaders nettyHeaders = new DefaultHttpHeaders();
        Http1Headers responseHeaders = new Http1Headers(nettyHeaders);
        MuWebSocket muWebSocket = factory.create(request, responseHeaders);
        if (muWebSocket == null) {
            return false;
        }
        NettyRequestAdapter reqImpl = (NettyRequestAdapter) request;
        boolean upgraded;
        try {
            upgraded = reqImpl.websocketUpgrade(muWebSocket, nettyHeaders, idleReadTimeoutMills, pingAfterWriteMillis, maxFramePayloadLength);
        } catch (UnsupportedOperationException e) {
            response.status(426);
            response.headers().set(HeaderNames.SEC_WEBSOCKET_VERSION, "13");
            return true;
        }
        if (upgraded) {
            ((NettyResponseAdaptor) response).setWebsocket();
        }
        return upgraded;
    }

    static boolean isWebSocketUpgrade(MuRequest request) {
        return request.headers().contains(HeaderNames.UPGRADE, HeaderValues.WEBSOCKET, true);
    }

}

