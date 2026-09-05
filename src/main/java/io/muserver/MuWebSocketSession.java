package io.muserver;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

/**
 * <p>A web socket session used to send messages and events to a web socket client.</p>
 * <p>The simplest way to get a reference to a session is to extend {@link BaseWebSocket} and use the {@link BaseWebSocket#session()} method.</p>
 */
public interface MuWebSocketSession {

    private void runAsync(Runnable task, DoneCallback doneCallback) {
        Executor executor = this instanceof WebsocketConnection
            ? ((WebsocketConnection) this).asyncExecutor() : ForkJoinPool.commonPool();
        CompletableFuture<Void> result;
        try { result = CompletableFuture.runAsync(task, executor); }
        catch (RuntimeException failure) { result = CompletableFuture.failedFuture(failure); }
        result.whenComplete((ignored, failure) -> {
            Throwable cause = failure instanceof java.util.concurrent.CompletionException ? failure.getCause() : failure;
            if (this instanceof WebsocketConnection) {
                ((WebsocketConnection) this).dispatchWriteCallback(doneCallback, cause);
            } else {
                try { doneCallback.onComplete(cause); }
                catch (Exception ignoredFailure) { }
            }
        });
    }

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
     * @throws IllegalStateException if a partial write is in progress
     * @throws IOException If the message cannot be written to the client.
     */
    void sendText(String message) throws IOException;

    /**
     * Sends a partial text message to the client
     * @param textFragment The partial text message, as a byte buffer containing a substring of UTF-8 encoded bytes
     * @param isLastFragment <code>true</code> if this is the last fragment of a partial text message
     * @throws IllegalStateException if a partial binary write is in progress
     * @throws IOException If the fragment cannot be written to the client.
     */
    void sendTextFragment(ByteBuffer textFragment, boolean isLastFragment) throws IOException;

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
        runAsync(() -> {
            try {
                sendText(message);
            } catch (Exception e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }, doneCallback);
    }


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
        runAsync(() -> {
            try {
                sendTextFragment(ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8)), isLastFragment);
            } catch (Exception e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }, doneCallback);
    }


    /**
     * Sends a full binary message to the client
     * @param message The message to be sent
     * @throws IllegalStateException if a partial write is in progress
     * @throws IOException If the message cannot be written to the client.
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
        runAsync(() -> {
            try {
                sendBinary(message);
            } catch (Exception e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }, doneCallback);
    }

    /**
     * Sends a partial message to the client
     * @param message The message to be sent
     * @param isLastFragment <code>true</code> if this is the last fragment of a message
     * @throws IllegalStateException if a partial text write is in progress
     * @throws IOException If the fragment cannot be written to the client.
     */
    void sendBinaryFragment(ByteBuffer message, boolean isLastFragment) throws IOException;

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
        runAsync(() -> {
            try {
                sendBinaryFragment(message, isLastFragment);
            } catch (Exception e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }, doneCallback);
    }

    /**
     * Sends a ping message to the client, which is used for keeping sockets alive.
     * @param payload The message to send.
     * @throws IOException If the ping cannot be written to the client.
     * @throws IllegalArgumentException if the payload exceeds 125 bytes
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
        runAsync(() -> {
            try {
                sendPing(payload);
            } catch (Exception e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }, doneCallback);
    }

    /**
     * Sends a pong message to the client, generally in response to receiving a ping via {@link MuWebSocket#onPing(ByteBuffer)}
     * @param payload The payload to send back to the client.
     * @throws IOException If the pong cannot be written to the client.
     * @throws IllegalArgumentException if the payload exceeds 125 bytes
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
        runAsync(() -> {
            try {
                sendPong(payload);
            } catch (Exception e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }, doneCallback);
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
     * @throws IllegalArgumentException if the UTF-8-encoded reason exceeds 123 bytes
     */
    void close(int statusCode, @Nullable String reason) throws IOException;

    /**
     * Gets the remote address of the connected client.
     *
     * @return The client's address
     */
    InetSocketAddress remoteAddress();

    /**
     * Gets the current lifecycle state of the websocket session.
     *
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
     * payload sent in the ping, then <code>null</code> is returned. Each outstanding ping is
     * measured at most once: repeated calls with the same payload, replayed pongs and pings
     * evicted from the bounded outstanding-ping history also return null.</p>
     *
     * @param pongPayload the payload received in a pong message. The buffer will be read from its current position
     *                    and after returning the position will not have been incremented.
     * @throws NullPointerException if pongPayload is null
     * @return the time taken in milliseconds from the time MuServer generated a ping message until
     * it processed the returned pong response, or <code>null</code> if it cannot be calculated.
     */
    @Nullable Long pongLatencyMillis(ByteBuffer pongPayload);

}
