package io.muserver;

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
//        HttpHeaders nettyHeaders = new DefaultHttpHeaders();
//        Http1Headers responseHeaders = new Http1Headers(nettyHeaders);
        var responseHeaders = new Mu3Headers();
        MuWebSocket muWebSocket = factory.create(request, responseHeaders);
        if (muWebSocket == null) {
            return false;
        }
        boolean upgraded;
        try {

//            upgraded = request.websocketUpgrade(muWebSocket, nettyHeaders, idleReadTimeoutMills, pingAfterWriteMillis, maxFramePayloadLength);
            upgraded = false;
        } catch (UnsupportedOperationException e) {
            response.status(426);
            response.headers().set(HeaderNames.SEC_WEBSOCKET_VERSION, "13");
            return true;
        }
        return upgraded;
    }

    static boolean isWebSocketUpgrade(MuRequest request) {
        return request.headers().contains(HeaderNames.UPGRADE, HeaderValues.WEBSOCKET, true);
    }

    @Override
    public String toString() {
        return "WebSocketHandler{" +
            "path='" + path + '\'' +
            ", idleReadTimeoutMills=" + idleReadTimeoutMills +
            ", pingAfterWriteMillis=" + pingAfterWriteMillis +
            ", maxFramePayloadLength=" + maxFramePayloadLength +
            '}';
    }
}

