package io.muserver;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

/**
 * <p>A low-level interface defining the callbacks received on a websocket which is returned by {@link MuWebSocketFactory#create(MuRequest, Headers)}.</p>
 * <p><strong>Note:</strong> Rather than implementing this, you may wish to extend the {@link SimpleWebSocket} class which
 * aggregates partial messages into full messages, handles ping and pong events and captures the socket session, exposing
 * it via the {@link SimpleWebSocket#session()} method.</p>
 * <p>In order to listen to events, extend the base class or implement this interface and store the reference to the {@link MuWebSocketSession} when
 * {@link #onConnect(MuWebSocketSession)} is called.</p>
 * <h2>Choosing between implementing this interface or extending <code>SimpleWebSocket</code>:</h2>
 * <ul>
 *     <li>For most cases where you just want to send and receive text and/or binary messages, extend
 *     {@link SimpleWebSocket}</li>
 *     <li>For cases where you desire lower level control over aspects such as handling message fragments,
 *     handling pings and pongs, and handling close events, implement this interface.</li>
 * </ul>
 */
public interface MuWebSocket {

    /**
     * Called when the websocket is connected.
     *
     * @param session The websocket session, which can be used to send messages, pings, and close the connection.
     * @throws Exception Any exceptions thrown will result in the onError method being called with the thrown exception being used as the <code>cause</code> parameter.
     */
    void onConnect(MuWebSocketSession session) throws Exception;

    /**
     * Called when a complete text message is received from the client.
     *
     * @param message    The message as a string.
     * @throws Exception Any exceptions thrown will result in the onError method being called with the thrown exception being used as the <code>cause</code> parameter.
     */
    void onText(String message) throws Exception;

   /**
     * Called when a text message fragment is received from the client.
    *
    * <p>Warning: the text message bytes may not be a valid UTF-8 string until it is concatenated with all fragments
    * of the message, which is why this provides a byte buffer rather than a partial String.</p>
     *
     * @param textFragment The partial text message as (incomplete) UTF-8 bytes
     * @param isLast       Returns <code>true</code> if this message is the last fragment of the complete message.
     * @throws Exception   Any exceptions thrown will result in the onError method being called with the thrown exception being used as the <code>cause</code> parameter.
     */
    void onTextFragment(ByteBuffer textFragment, boolean isLast) throws Exception;

    /**
     * Called when a complete binary message is received from the client.
     *
     * @param buffer     The message as a byte buffer.
     * @throws Exception Any exceptions thrown will result in the onError method being called with the thrown exception being used as the <code>cause</code> parameter.
     */
    void onBinary(ByteBuffer buffer) throws Exception;

    /**
     * Called when a partial binary message is received from the client.
     *
     * @param buffer     The fragment as a byte buffer.
     * @param isLast     Returns <code>true</code> if this is the last fragment of the complete message.
     * @throws Exception Any exceptions thrown will result in the onError method being called with the thrown exception being used as the <code>cause</code> parameter.
     */
    void onBinaryFragment(ByteBuffer buffer, boolean isLast) throws Exception;

    /**
     * Called when the client has sent a close frame.
     * <p>The connection should be closed on the server side when this is received, unless the server initiated the
     * close sequence in which case it can be ignored. If overriding {@link BaseWebSocket} this occurs automatically.</p>
     *
     * @param statusCode The closure code. See <a href="https://tools.ietf.org/html/rfc6455#section-7.4">https://tools.ietf.org/html/rfc6455#section-7.4</a>
     * @param reason     An optional reason for the closure.
     * @throws Exception Any exceptions thrown will result in the onError method being called with the thrown exception being used as the <code>cause</code> parameter.
     */
    void onClientClosed(int statusCode, String reason) throws Exception;

    /**
     * Called when a ping message is sent from a client.
     * <p>When received, a websocket should send the data back in a <code>pong</code> message. If overriding {@link BaseWebSocket} this occurs automatically.</p>
     *
     * @param payload    The ping payload.
     * @throws Exception Any exceptions thrown will result in the onError method being called with the thrown exception being used as the <code>cause</code> parameter.
     */
    void onPing(ByteBuffer payload) throws Exception;

    /**
     * Called when a pong message is sent from the client.
     *
     * @param payload    The pong payload
     * @throws Exception Any exceptions thrown will result in the onError method being called with the thrown exception being used as the <code>cause</code> parameter.
     */
    void onPong(ByteBuffer payload) throws Exception;

    /**
     * Called when an unexpected error occurs. Possible errors include, but are not limited to:
     * <ul>
     *     <li>The client shuts down non-gracefully in which case the cause will be a {@link ClientDisconnectedException}
     *     (note that if the client initiates a graceful shutdown,
     *     then {@link #onClientClosed(int, String)} will be called instead)</li>
     *     <li>No messages have been received within the time specified by {@link WebSocketHandlerBuilder#withIdleReadTimeout(long, TimeUnit)},
     *     in which case the cause will be a {@link java.util.concurrent.TimeoutException}</li>
     *     <li>An Exception is thrown by any of the methods that implement this interface, such as
     *     {@link #onText(String)} etc (but not onError itself).</li>
     *     <li>The client sends an invalid frame</li>
     * </ul>
     *
     * @param cause The cause of the error
     * @throws Exception Any exceptions thrown will result in the connection being closed.
     */
    void onError(Throwable cause) throws Exception;

    /**
     * Called when the server is being shut down
     * @throws Exception Any exceptions thrown will result in the connection being closed.
     */
    void onServerShuttingDown() throws Exception;

}
