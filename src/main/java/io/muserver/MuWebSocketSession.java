package io.muserver;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * <p>A web socket session used to send messages and events to a web socket client.</p>
 * <p>The simplest way to get a reference to a session is to extend {@link BaseWebSocket} and use the {@link BaseWebSocket#session()} method.</p>
 */
public interface MuWebSocketSession {

    /**
     * Specifies whether a close frame sent from the client has been received
     * @return <code>true</code> if a close frame has been received from the client
     */
    boolean closeReceived();

    /**
     * Specifies whether a close frame has been sent to the client
     * @return <code>true</code> if a close frame has been sent
     */
    boolean closeSent();

    /**
     * Sends a text message to the client
     * @param message The message to be sent
     */
    void sendText(String message) throws IOException;

    /**
     * Sends a message to the client asynchronously
     * @param message The message to be sent
     * @param doneCallback The callback to call when the write succeeds or fails. To ignore the write result, you can
     *                      use {@link DoneCallback#NoOp}. If using a buffer received from a {@link MuWebSocket} event,
     *                      pass the <code>onComplete</code> received to this parameter.
     * @deprecated Non-blocking operations no longer supported. Use the blocking {@link #sendText(String)} instead
     */
    @Deprecated
    default void sendText(String message, DoneCallback doneCallback) {
        CompletableFuture.runAsync(() -> {
            try {
                sendText(message);
                doneCallback.onComplete(null);
            } catch (Exception e) {
                try {
                    doneCallback.onComplete(e);
                } catch (Exception ignored) {
                }
            }
        });
    }

    /**
     * Sends a full or partial message to the client asynchronously with an optional parameter allowing partial fragments to be sent
     * @param message The message to be sent
     * @param isLastFragment If <code>false</code> then this message will be sent as a partial fragment
     */
    void sendText(String message, boolean isLastFragment) throws IOException;

    /**
     * Sends a full or partial message to the client asynchronously with an optional parameter allowing partial fragments to be sent
     * @param message The message to be sent
     * @param isLastFragment If <code>false</code> then this message will be sent as a partial fragment
     * @param doneCallback The callback to call when the write succeeds or fails. To ignore the write result, you can
     *                      use {@link DoneCallback#NoOp}. If using a buffer received from a {@link MuWebSocket} event,
     *                      pass the <code>onComplete</code> received to this parameter.
     * @deprecated Non-blocking operations no longer supported. Use the blocking {@link #sendText(String)} instead
     */
    @Deprecated
    default void sendText(String message, boolean isLastFragment, DoneCallback doneCallback) {
        CompletableFuture.runAsync(() -> {
            try {
                sendText(message, isLastFragment);
                doneCallback.onComplete(null);
            } catch (Exception e) {
                try {
                    doneCallback.onComplete(e);
                } catch (Exception ignored) {
                }
            }
        });
    }


    /**
     * Sends a message to the client asynchronously
     * @param message The message to be sent
     */
    void sendBinary(ByteBuffer message) throws IOException;

    /**
     * Sends a message to the client asynchronously
     * @param message The message to be sent
     * @param doneCallback The callback to call when the write succeeds or fails. To ignore the write result, you can
     *                      use {@link DoneCallback#NoOp}. If using a buffer received from a {@link MuWebSocket} event,
     *                      pass the <code>onComplete</code> received to this parameter.
     * @deprecated Non-blocking operations no longer supported. Use the blocking {@link #sendText(String)} instead
     */
    @Deprecated
    default void sendBinary(ByteBuffer message, DoneCallback doneCallback) {
        CompletableFuture.runAsync(() -> {
            try {
                sendBinary(message);
                doneCallback.onComplete(null);
            } catch (Exception e) {
                try {
                    doneCallback.onComplete(e);
                } catch (Exception ignored) {
                }
            }
        });
    }

    /**
     * Sends a full or partial message to the client asynchronously with an optional parameter allowing partial fragments to be sent
     * @param message The message to be sent
     * @param isLastFragment If <code>false</code> then this message will be sent as a partial fragment
     */
    void sendBinary(ByteBuffer message, boolean isLastFragment) throws IOException;

    /**
     * Sends a full or partial message to the client asynchronously with an optional parameter allowing partial fragments to be sent
     * @param message The message to be sent
     * @param isLastFragment If <code>false</code> then this message will be sent as a partial fragment
     * @param doneCallback The callback to call when the write succeeds or fails. To ignore the write result, you can
     *                      use {@link DoneCallback#NoOp}. If using a buffer received from a {@link MuWebSocket} event,
     *                      pass the <code>onComplete</code> received to this parameter.
     * @deprecated Non-blocking operations no longer supported. Use the blocking {@link #sendText(String)} instead
     */
    @Deprecated
    default void sendBinary(ByteBuffer message, boolean isLastFragment, DoneCallback doneCallback) {
        CompletableFuture.runAsync(() -> {
            try {
                sendBinary(message, isLastFragment);
                doneCallback.onComplete(null);
            } catch (Exception e) {
                try {
                    doneCallback.onComplete(e);
                } catch (Exception ignored) {
                }
            }
        });
    }

    /**
     * Sends a ping message to the client, which is used for keeping sockets alive.
     * @param payload The message to send.
     */
    void sendPing(ByteBuffer payload) throws IOException;

    /**
     * Sends a ping message to the client, which is used for keeping sockets alive.
     * @param payload The message to send.
     * @param doneCallback The callback to call when the write succeeds or fails. To ignore the write result, you can
     *                      use {@link DoneCallback#NoOp}. If using a buffer received from a {@link MuWebSocket} event,
     *                      pass the <code>onComplete</code> received to this parameter.
     * @deprecated Non-blocking operations no longer supported. Use the blocking {@link #sendText(String)} instead
     */
    @Deprecated
    default void sendPing(ByteBuffer payload, DoneCallback doneCallback) {
        CompletableFuture.runAsync(() -> {
            try {
                sendPing(payload);
                doneCallback.onComplete(null);
            } catch (Exception e) {
                try {
                    doneCallback.onComplete(e);
                } catch (Exception ignored) {
                }
            }
        });
    }

    /**
     * Sends a pong message to the client, generally in response to receiving a ping via {@link MuWebSocket#onPing(ByteBuffer)}
     * @param payload The payload to send back to the client.
     */
    void sendPong(ByteBuffer payload) throws IOException;

    /**
     * Sends a pong message to the client, generally in response to receiving a ping via {@link MuWebSocket#onPing(ByteBuffer)}
     * @param payload The payload to send back to the client.
     * @param doneCallback The callback to call when the write succeeds or fails. To ignore the write result, you can
     *                      use {@link DoneCallback#NoOp}. If using a buffer received from a {@link MuWebSocket} event,
     *                      pass the <code>onComplete</code> received to this parameter.
     * @deprecated Non-blocking operations no longer supported. Use the blocking {@link #sendText(String)} instead
     */
    @Deprecated
    default void sendPong(ByteBuffer payload, DoneCallback doneCallback) {
        CompletableFuture.runAsync(() -> {
            try {
                sendPong(payload);
                doneCallback.onComplete(null);
            } catch (Exception e) {
                try {
                    doneCallback.onComplete(e);
                } catch (Exception ignored) {
                }
            }
        });
    }

    /**
     * Initiates a graceful shutdown with the client with no reason code specified
     * @throws IOException Thrown if there is an error writing to the client, for example if the user has closed their browser.
     */
    void close() throws IOException;

    /**
     * Initiates a graceful shutdown with the client.
     * @param statusCode The status code to send, such as <code>1000</code>. See <a href="https://tools.ietf.org/html/rfc6455#section-7.4">https://tools.ietf.org/html/rfc6455#section-7.4</a>
     * @param reason An optional reason for closing.
     * @throws IOException Thrown if there is an error writing to the client, for example if the user has closed their browser.
     */
    void close(int statusCode, String reason) throws IOException;

    /**
     * @return The client's address
     */
    InetSocketAddress remoteAddress();

    /**
     * @return The state of the current session
     */
    WebsocketSessionState state();

    /**
     * Calculates the time taken between a ping initiated from this server until the client pong
     * response is loaded.
     *
     * <p>Note: this is not pure network latency as operations such as processing other messages
     * on this server may delay the processing time of the pong event.</p>
     *
     * <p>Note 2: This only calculates latency for pings automatically sent by MuServer which
     * are configured using {@link WebSocketHandlerBuilder#withPingInterval(int, TimeUnit)}.
     * If the ping was initiated by your own code by calling {@link MuWebSocketSession#sendPing(ByteBuffer)}
     * or the client sent an unsolicited pong message, or the pong response does not contain the
     * payload sent in the ping, then <code>null</code> is returned.</p>
     *
     * @param pongPayload the payload received in a pong message. The buffer will be read from its current position
     *                    and after returning the position will not have been incremented.
     * @throws NullPointerException if pongPayload is null
     * @return the time taken in milliseconds from the time MuServer generated a ping message until
     * it processed the returned pong response, or <code>null</code> if it cannot be calculated.
     */
    Long pongLatencyMillis(ByteBuffer pongPayload);

}
