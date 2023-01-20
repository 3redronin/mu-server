package io.muserver;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

/**
 * <p>An interface defining the callbacks received on a websocket which is returned by {@link MuWebSocketFactory#create(MuRequest, Headers)}.</p>
 * <p>In order to listen to events, implement this interface and store the reference to the {@link MuWebSocketSession} when
 * {@link #onConnect(MuWebSocketSession)} is called.</p>
 * <p><strong>Important:</strong> The callbacks are called within on an NIO event thread, therefore there should be no
 * blocking calls in the callbacks (any blocking IO should be passed to another thread). The methods that receive a
 * {@link ByteBuffer} in this interface provide a {@link DoneCallback} parameter which should be called when
 * the buffer is no longer needed. <em>If this is not called, then no more messages will be received.</em></p>
 * <p><strong>Note:</strong> Rather than implementing this, you may wish to extend the {@link BaseWebSocket} class which
 * handles ping events and captures the socket session, exposing it via the {@link BaseWebSocket#session()} method.</p>
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
     * Called when a message is received from the client.
     *
     * @param message    The message as a string.
     * @param onComplete A callback that must be run with <code>onComplete.run()</code> when the byte buffer is no longer needed.
     * @throws Exception Any exceptions thrown will result in the onError method being called with the thrown exception being used as the <code>cause</code> parameter.
     * @deprecated use {@link #onText(String, boolean, DoneCallback)} instead
     */
    @Deprecated
    default void onText(String message, DoneCallback onComplete) throws Exception {
        onComplete.onComplete(null);
    }

    /**
     * Called when a message is received from the client.
     *
     * @param message    The message as a string.
     * @param isLast     Returns <code>true</code> if this message is the last part of the complete message. This is only <code>false</code>
     *                   when clients send fragmented messages in which case only the last part of the fragmented message will return <code>true</code>.
     * @param onComplete A callback that must be run with <code>onComplete.run()</code> when the byte buffer is no longer needed.
     * @throws Exception Any exceptions thrown will result in the onError method being called with the thrown exception being used as the <code>cause</code> parameter.
     */
    default void onText(String message, boolean isLast, DoneCallback onComplete) throws Exception {
        onText(message, onComplete);
    }

    /**
     * Called when a message is received from the client.
     *
     * @param buffer     The message as a byte buffer.
     * @param onComplete A callback that must be run with <code>onComplete.run()</code> when the byte buffer is no longer needed. Failure to call this will result in memory leaks.
     * @throws Exception Any exceptions thrown will result in the onError method being called with the thrown exception being used as the <code>cause</code> parameter.
     * @deprecated use {@link #onBinary(ByteBuffer, boolean, DoneCallback)} instead
     */
    @Deprecated
    default void onBinary(ByteBuffer buffer, DoneCallback onComplete) throws Exception {
        onComplete.onComplete(null);
    }

    /**
     * Called when a message is received from the client.
     *
     * @param buffer     The message as a byte buffer.
     * @param isLast     Returns <code>true</code> if this message is the last part of the complete message. This is only <code>false</code>
     *                   when clients send fragmented messages in which case only the last part of the fragmented message will return <code>true</code>.
     * @param onComplete A callback that must be run with <code>onComplete.run()</code> when the byte buffer is no longer needed. Failure to call this will result in memory leaks.
     * @throws Exception Any exceptions thrown will result in the onError method being called with the thrown exception being used as the <code>cause</code> parameter.
     */
    default void onBinary(ByteBuffer buffer, boolean isLast, DoneCallback onComplete) throws Exception {
        onBinary(buffer, onComplete);
    }

    /**
     * Called when a message is received from the client. Consider using this API when separation of control for pulling data and releasing buffer are required.
     * Otherwise, please use {@link #onBinary(ByteBuffer, boolean, DoneCallback)} instead.
     *
     * @param buffer     The message as a byte buffer.
     * @param isLast     Returns <code>true</code> if this message is the last part of the complete message. This is only <code>false</code>
     *                   when clients send fragmented messages in which case only the last part of the fragmented message will return <code>true</code>.
     * @param doneAndPullData A callback that must be run with <code>doneAndPullData.onComplete()</code> when ready to pull more data from websocket.
     * @param releaseBuffer A callback that must be run with <code>releaseBuffer.run()</code> when the byte buffer is no longer needed. Failure to call this will result in memory leaks.
     * @throws Exception Any exceptions thrown will result in the onError method being called with the thrown exception being used as the <code>cause</code> parameter.
     */
    default void onBinary(ByteBuffer buffer, boolean isLast, DoneCallback doneAndPullData, Runnable releaseBuffer) throws Exception {
        onBinary(buffer, isLast, error -> {
            releaseBuffer.run();
            doneAndPullData.onComplete(error);
        });
    }

    /**
     * Called when the client has closed the connection.
     * <p>The connection should be closed on the server side when this is received. If overriding {@link BaseWebSocket} this occurs automatically.</p>
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
     * @param onComplete A callback that must be run with <code>onComplete.run()</code> when the byte buffer is no longer needed. Failure to call this will result in memory leaks.
     * @throws Exception Any exceptions thrown will result in the onError method being called with the thrown exception being used as the <code>cause</code> parameter.
     */
    void onPing(ByteBuffer payload, DoneCallback onComplete) throws Exception;

    /**
     * Called when a pong message is sent from the client.
     *
     * @param payload    The pong payload
     * @param onComplete A callback that must be run with <code>onComplete.run()</code> when the byte buffer is no longer needed. Failure to call this will result in memory leaks.
     * @throws Exception Any exceptions thrown will result in the onError method being called with the thrown exception being used as the <code>cause</code> parameter.
     */
    void onPong(ByteBuffer payload, DoneCallback onComplete) throws Exception;

    /**
     * Called when an unexpected error occurs. Possible errors include, but are not limited to:
     * <ul>
     *     <li>The client shuts down non-gracefully in which case the cause will be a {@link ClientDisconnectedException}
     *     (note that if the client initiates a graceful shutdown,
     *     then {@link #onClientClosed(int, String)} will be called instead)</li>
     *     <li>No messages have been received within the time specified by {@link WebSocketHandlerBuilder#withIdleReadTimeout(long, TimeUnit)},
     *     in which case the cause will be a {@link java.util.concurrent.TimeoutException}</li>
     *     <li>An Exception is thrown by any of the methods that implement this interface, such as
     *     {@link #onText(String, DoneCallback)} etc (but not onError itself).</li>
     *     <li>The client sends an invalid frame</li>
     * </ul>
     *
     * @param cause The cause of the error
     * @throws Exception Any exceptions thrown will result in the connection being closed.
     */
    void onError(Throwable cause) throws Exception;
}
