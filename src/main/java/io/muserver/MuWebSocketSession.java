package io.muserver;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

public interface MuWebSocketSession {

    void sendText(String message);

    void sendBinary(ByteBuffer message);

    void sendPing(ByteBuffer payload);

    void sendPong(ByteBuffer payload);

    void close();

    void close(int statusCode, String reason);

    void disconnect();

    InetSocketAddress remoteAddress();
}
