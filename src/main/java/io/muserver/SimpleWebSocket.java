package io.muserver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A base class for server-side web sockets, that takes care of capturing the web socket session, responding
 * to pings, and closure events.
 *
 * <p>The {@link #session()} method can be used to access the session object that allows the sending of messages
 * to clients.</p>
 *
 * <p>Implementers of this class just need to override {@link #onBinary(ByteBuffer)} and {@link #onText(String)}
 * methods. Other methods can be overridden for more advanced control.</p>
 *
 * <p>By default, fragments are aggregated into complete messages unless {@link #onTextFragment(ByteBuffer, boolean)}
 * or {@link #onBinaryFragment(ByteBuffer, boolean)} are overriden</p>
 *
 * <p>This is an alternative to implementing the {@link MuWebSocket} interface and is recommended so that any
 * additions to the interface are non-breaking to implementors.</p>
 */
@SuppressWarnings("RedundantThrows") // because implementing classes might throw exceptions
public abstract class SimpleWebSocket implements MuWebSocket {
    private MuWebSocketSession session;
    private NiceByteArrayOutputStream fragmentBuffer;

    /**
     * @return The state of the current session
     */
    protected WebsocketSessionState state() {
        MuWebSocketSession session = this.session;
        return session == null ? WebsocketSessionState.NOT_STARTED : session.state();
    }

    /**
     * Stores reference to the session, exposing it in the {@link #session()} method
     */
    @Override
    public void onConnect(MuWebSocketSession session) throws Exception {
        this.session = session;
    }


    /**
     * <p>If this is the start of a client-initiated shut down, then a close frame with the same status
     * and reason is echoed back to the client.</p>
     * <p>If the close event is in response to the server closing the websocket the event is ignored.</p>
     */
    @Override
    public void onClientClosed(int statusCode, String reason) throws Exception {
        if (!session().closeSent()) {
            if (statusCode == 1005) {
                // the client didn't send a code so we won't either
                session.close();
            } else {
                session.close(statusCode, reason);
            }
        }
    }


    /**
     * Buffers the fragment in memory until the last fragment is received, whereby {@link #onText(String)} with
     * the full message is invoked
     * @param textFragment The partial text message as (incomplete) UTF-8 bytes
     * @param isLast       Returns <code>true</code> if this message is the last fragment of the complete message.
     */
    @Override
    public void onTextFragment(ByteBuffer textFragment, boolean isLast) throws Exception {
        bufferIt(textFragment);
        if (isLast) {
            onText(fragmentBuffer.decodeUTF8());
            fragmentBuffer = null;
        }
    }

    /**
     * Buffers the fragment in memory until the last fragment is received, whereby {@link #onBinary(ByteBuffer)} with
     * the full message is invoked
     * @param buffer     The fragment as a byte buffer.
     * @param isLast     Returns <code>true</code> if this is the last fragment of the complete message.
     */
    @Override
    public void onBinaryFragment(ByteBuffer buffer, boolean isLast) throws Exception {
        bufferIt(buffer);

        if (isLast) {
            var full = fragmentBuffer.toByteBuffer();
            fragmentBuffer = null;
            onBinary(full);
        }
    }

    private void bufferIt(ByteBuffer buffer) throws IOException {
        if (fragmentBuffer == null) {
            fragmentBuffer = new NiceByteArrayOutputStream(buffer.remaining());
        }
        if (buffer.hasArray()) {
            fragmentBuffer.write(buffer.array(), buffer.arrayOffset() + buffer.position(), buffer.remaining());
        } else {
            var temp = new byte[buffer.remaining()];
            buffer.get(temp);
            fragmentBuffer.write(temp);
        }
    }

    /**
     * Echoes the received payload back to the peer as a pong event
     * @param payload the payload the peer sent in their ping frame
     */
    @Override
    public void onPing(ByteBuffer payload) throws Exception {
        session().sendPong(payload);
    }

    /**
     * If this pong is a response to a latency ping, then the latency is calculated
     * and {@link #averagePingPongLatencyMillis()} is updated.
     *
     * <p>Unrecognised pong messages are silently ignored.</p>
     */
    @Override
    public void onPong(ByteBuffer payload) throws Exception {
        Long latency = session().pongLatencyMillis(payload);
        if (latency != null) {
            latencyChecks.incrementAndGet();
            latencyTime.incrementAndGet();
        }
    }

    /**
     * Gets the average latency in milliseconds.
     *
     * <p>This is the average time taken for this websocket to send a ping message to a client
     * until it receives the pong message back.</p>
     *
     * <p>Only ping messages automatically sent (configured with {@link WebSocketHandlerBuilder#withPingInterval(int, TimeUnit)})
     * are included.</p>
     * @return The average time in latency, or <code>null</code> if unknown
     */
    public Long averagePingPongLatencyMillis() {
        var pongs = latencyChecks.get();
        if (pongs == 0L) return null;
        return latencyTime.get() / pongs;
    }

    private final AtomicLong latencyChecks = new AtomicLong(0);
    private final AtomicLong latencyTime = new AtomicLong(0);

    /**
     * Sends a close event if the session is open and one hasn't been sent
     */
    @Override
    public void onError(Throwable cause) throws Exception {
        if (!state().endState() && !session().closeSent()) {
            if (cause instanceof TimeoutException || cause instanceof SocketTimeoutException) {
                session().close(3008, WebsocketSessionState.TIMED_OUT.name());
            } else if (cause instanceof CharacterCodingException) {
                session().close(1007, "Non UTF-8 data in text frame");
            } else if (session != null && !(cause instanceof ClientDisconnectedException)) {
                session().close(1011, WebsocketSessionState.ERRORED.name());
            }
        }
    }

    /**
     * Gets the websocket session
     *
     * @return A session that can be used to send message and events to the client.
     * @throws IllegalStateException Thrown if the socket has not been connected yet.
     */
    protected MuWebSocketSession session() {
        if (session == null) {
            throw new IllegalStateException("The websocket has not been connected yet");
        }
        return session;
    }

    /**
     * Sends a close frame with code 1001 ("Going away") to the client
     */
    @Override
    public void onServerShuttingDown() throws Exception {
        onClientClosed(1001, null);
    }

    /**
     * A BAOS that lets you get the raw buffer without making a copy of it
     */
    private static class NiceByteArrayOutputStream extends ByteArrayOutputStream {
        public NiceByteArrayOutputStream(int size) {
            super(size);
        }

        ByteBuffer toByteBuffer() {
            return ByteBuffer.wrap(buf, 0, count);
        }

        String decodeUTF8() throws CharacterCodingException {
            var charBuffer = StandardCharsets.UTF_8.newDecoder().decode(toByteBuffer());
            return charBuffer.toString();
        }
    }
}
