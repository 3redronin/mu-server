package io.muserver;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

/**
 * <p>A web socket session used to send messages and events to a web socket client.</p>
 * <p>The simplest way to get a reference to a session is to extend {@link BaseWebSocket} and use the {@link BaseWebSocket#session()} method.</p>
 */
public interface MuWebSocketSession {

    /**
     * Sends a message to the client
     * @param message The message to be sent
     */
    void sendText(String message);

    /**
     * Sends a message to the client
     * @param message The message to be sent
     */
    void sendBinary(ByteBuffer message);

    /**
     * Sends a ping message to the client, which is used for keeping sockets alive.
     * @param payload The message to send.
     */
    void sendPing(ByteBuffer payload);

    /**
     * Sends a pong message to the client, generally in response to receiving a ping via {@link MuWebSocket#onPing(ByteBuffer)}
     * @param payload The payload to send back to the client.
     */
    void sendPong(ByteBuffer payload);

    /**
     * Initiates a graceful shutdown with the client.
     */
    void close();

    /**
     * Initiates a graceful shutdown with the client.
     * @param statusCode The status code to send, such as <code>1000</code>. See <a href="https://tools.ietf.org/html/rfc6455#section-7.4">https://tools.ietf.org/html/rfc6455#section-7.4</a>
     * @param reason An optional reason for closing.
     */
    void close(int statusCode, String reason);

    InetSocketAddress remoteAddress();
}
