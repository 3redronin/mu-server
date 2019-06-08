package io.muserver;

import java.nio.ByteBuffer;
import java.util.Map;

public class WebSocketHandler implements MuHandler, RouteHandler {

    private final MuWebSocketFactory factory;

    WebSocketHandler(MuWebSocketFactory factory) {
        this.factory = factory;
    }

    @Override
    public boolean handle(MuRequest request, MuResponse response) throws Exception {

        MuWebSocket muWebSocket = factory.create(request);
        if (muWebSocket == null) {
            return false;
        }
        NettyRequestAdapter reqImpl = (NettyRequestAdapter) request;
        boolean upgraded = reqImpl.websocketUpgrade(muWebSocket);
        if (upgraded) {
            ((NettyResponseAdaptor)response).setWebsocket();
        }
        return upgraded;
    }

    @Override
    public void handle(MuRequest request, MuResponse response, Map<String, String> pathParams) throws Exception {
        handle(request, response);
    }
}

interface MuWebSocketFactory {

    MuWebSocket create(MuRequest request) throws Exception;

}

interface MuWebSocket {
    void onConnect(MuWebSocketSession session) throws Exception;
    void onText(String message) throws Exception;
    void onBinary(ByteBuffer buffer) throws Exception;
    void onClose(int statusCode, String reason) throws Exception;
    void onPing(ByteBuffer payload) throws Exception;
    void onPong(ByteBuffer payload) throws Exception;
}

