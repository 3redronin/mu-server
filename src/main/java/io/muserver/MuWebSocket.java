package io.muserver;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

/**
 * <p>An interface defining the callbacks received on a websocket which is returned by {@link MuWebSocketFactory#create(MuRequest)}.</p>
 * <p>In order to listen to events, implement this interface and store the reference to the {@link MuWebSocketSession} when
 * {@link #onConnect(MuWebSocketSession)} is called.</p>
 * <p><strong>Note:</strong> Rather than implementing this, you may wish to extend the {@link BaseWebSocket} class which
 * handles ping events and captures the socket session, exposing it via the {@link BaseWebSocket#session()} method.</p>
 */
public interface MuWebSocket {

    /**
     * Called when the websocket is connected.
     * @param session The websocket session, which can be used to send messages, pings, and close the connection.
     * @throws Exception Any exceptions thrown will result in the connection being closed.
     */
    void onConnect(MuWebSocketSession session) throws Exception;

    /**
     * Called when a message is received from the client.
     * @param message The message as a string.
     * @throws Exception Any exceptions thrown will result in the connection being closed.
     */
    void onText(String message) throws Exception;

    /**
     * Called when a message is received from the client.
     * @param buffer The message as a byte buffer.
     * @throws Exception Any exceptions thrown will result in the connection being closed.
     */
    void onBinary(ByteBuffer buffer) throws Exception;

    /**
     * Called when the client has closed the connection.
     * @param statusCode The closure code. See <a href="https://tools.ietf.org/html/rfc6455#section-7.4">https://tools.ietf.org/html/rfc6455#section-7.4</a>
     * @param reason An optional reason for the closure.
     * @throws Exception Any exceptions thrown will result in the connection being closed.
     */
    void onClose(int statusCode, String reason) throws Exception;

    /**
     * Called when a ping message is sent from a client.
     * @param payload The ping payload.
     * @throws Exception Any exceptions thrown will result in the connection being closed.
     */
    void onPing(ByteBuffer payload) throws Exception;

    /**
     * Called when a pong message is sent from the client.
     * @param payload The pong payload
     * @throws Exception Any exceptions thrown will result in the connection being closed.
     */
    void onPong(ByteBuffer payload) throws Exception;

    /**
     * Called when no messages have been sent or received for the time specified by
     * {@link WebSocketHandlerBuilder#withIdleReadTimeout(long, TimeUnit)}
     * @throws Exception Any exceptions thrown will result in the connection being closed.
     */
    void onIdleReadTimeout() throws Exception;
}
