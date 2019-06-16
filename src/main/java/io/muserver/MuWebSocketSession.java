package io.muserver;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

/**
 * <p>A web socket session used to send messages and events to a web socket client.</p>
 * <p>The simplest way to get a reference to a session is to extend {@link BaseWebSocket} and use the {@link BaseWebSocket#session()} method.</p>
 */
public interface MuWebSocketSession {

    /**
     * Sends a message to the client asynchronously
     * @param message The message to be sent
     * @param doneCallback The callback to call when the write succeeds or fails. To ignore the write result, you can
     *                      use {@link DoneCallback#NoOp}. If using a buffer received from a {@link MuWebSocket} event,
     *                      pass the <code>onComplete</code> received to this parameter.
     */
    void sendText(String message, DoneCallback doneCallback);

    /**
     * Sends a message to the client
     * @param message The message to be sent
     * @param doneCallback The callback to call when the write succeeds or fails. To ignore the write result, you can
     *                      use {@link DoneCallback#NoOp}. If using a buffer received from a {@link MuWebSocket} event,
     *                      pass the <code>onComplete</code> received to this parameter.
     */
    void sendBinary(ByteBuffer message, DoneCallback doneCallback);

    /**
     * Sends a ping message to the client, which is used for keeping sockets alive.
     * @param payload The message to send.
     * @param doneCallback The callback to call when the write succeeds or fails. To ignore the write result, you can
     *                      use {@link DoneCallback#NoOp}. If using a buffer received from a {@link MuWebSocket} event,
     *                      pass the <code>onComplete</code> received to this parameter.
     */
    void sendPing(ByteBuffer payload, DoneCallback doneCallback);

    /**
     * Sends a pong message to the client, generally in response to receiving a ping via {@link MuWebSocket#onPing(ByteBuffer, DoneCallback)}
     * @param payload The payload to send back to the client.
     * @param doneCallback The callback to call when the write succeeds or fails. To ignore the write result, you can
     *                      use {@link DoneCallback#NoOp}. If using a buffer received from a {@link MuWebSocket} event,
     *                      pass the <code>onComplete</code> received to this parameter.
     */
    void sendPong(ByteBuffer payload, DoneCallback doneCallback);

    /**
     * Initiates a graceful shutdown with the client.
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
}
