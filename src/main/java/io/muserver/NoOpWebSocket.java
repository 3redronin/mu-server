package io.muserver;

import java.nio.ByteBuffer;

public class NoOpWebSocket implements MuWebSocket {
    @Override
    public void onConnect(MuWebSocketSession session) throws Exception {
    }

    @Override
    public void onText(String message) throws Exception {
    }

    @Override
    public void onBinary(ByteBuffer buffer) throws Exception {
    }

    @Override
    public void onClose(int statusCode, String reason) throws Exception {
    }

    @Override
    public void onPing(ByteBuffer payload) throws Exception {
    }

    @Override
    public void onPong(ByteBuffer payload) throws Exception {
    }
}
