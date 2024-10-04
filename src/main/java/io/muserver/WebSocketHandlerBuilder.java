package io.muserver;

import java.util.concurrent.TimeUnit;

/**
 * <p>Used to create handlers for web sockets.</p>
 */
public class WebSocketHandlerBuilder implements MuHandlerBuilder<WebSocketHandler> {

    private MuWebSocketFactory factory;
    private String path;
    private long pingIntervalMillis = TimeUnit.SECONDS.toMillis(60);
    private long pongTimeoutMillis = TimeUnit.SECONDS.toMillis(20);
    private int maxFramePayloadLength = 65536;
    private long maxMessageLength = maxFramePayloadLength;

    /**
     * <p>Sets the factory that decides whether to create a websocket connection for a request.</p>
     * <p>Note that the factory will only be called if the request is a websocket upgrade request.</p>
     * @param factory A factory that creates websockets, or returns null if the websocket connection shouldn't
     *                be created.
     * @return This builder
     */
    public WebSocketHandlerBuilder withWebSocketFactory(MuWebSocketFactory factory) {
        Mutils.notNull("factory", factory);
        this.factory = factory;
        return this;
    }

    /**
     * Sets the path to listen on for this handler. Note that an alternative to setting a path is
     * to look at {@link MuRequest#uri()} in the {@link MuWebSocketFactory} and decide whether to
     * handle the request or not.
     * <p>Note: if this handler is nested within a {@link ContextHandler}, then this is the relative path.</p>
     * @param path The path of this web socket endpoint.
     * @return This builder
     */
    public WebSocketHandlerBuilder withPath(String path) {
        this.path = path;
        return this;
    }

    /**
     * Ignored
     * @param duration ignored
     * @param unit ignored
     * @return This builder
     * @deprecated timeouts are controlled with ping events, configured with {@link #withPingInterval(int, TimeUnit)}
     * and {@link #withPongResponseTimeout(int, TimeUnit)}
     */
    @Deprecated
    public WebSocketHandlerBuilder withIdleReadTimeout(long duration, TimeUnit unit) {
        return this;
    }

    /**
     * Ignored
     * @param duration Ignored
     * @param unit Ignored
     * @return This builder
     * @deprecated pings are now sent on an interval, configured with {@link #withPingInterval(int, TimeUnit)}
     */
    @Deprecated
    public WebSocketHandlerBuilder withPingSentAfterNoWritesFor(int duration, TimeUnit unit) {
        return this;
    }

    /**
     * Specifies how frequently ping messages will be sent to clients.
     *
     * <p>If a pong is not received in response to a ping within the timeout specified by
     * {@link #withPongResponseTimeout(int, TimeUnit)} then the connection will be closed.</p>
     *
     * <p>The default is 60 seconds.</p>
     *
     * @param interval The approximate frequency in which pings are sent, or 0 to disable pings.
     * @param unit The unit of the interval.
     * @return This builder
     */
    public WebSocketHandlerBuilder withPingInterval(int interval, TimeUnit unit) {
        if (interval < 0) {
            throw new IllegalArgumentException("The interval must be 0 or greater");
        }
        Mutils.notNull("unit", unit);
        this.pingIntervalMillis = unit.toMillis(interval);
        return this;
    }

    /**
     * This is used to detect unresponsive or non-gracefully disconnected clients.
     *
     * <p>When a ping message is automatically send (the interval is configured with {@link #withPingInterval(int, TimeUnit)})
     * the client is expected to respond with a pong message.</p>
     *
     * <p>If no pong message is received in the time specified with this timeout value, then the client is considered
     * to be disconnected, and the socket will be closed (a 1002 closure code will be sent to the client and the connection
     * will be disconnected without waiting for a corresponding client close event).</p>
     *
     * @param duration The allowed time to wait for a pong response, or 0 to disable timeouts.
     * @param unit The unit of the duration.
     * @return This builder
     */
    public WebSocketHandlerBuilder withPongResponseTimeout(int duration, TimeUnit unit) {
        if (duration < 0) {
            throw new IllegalArgumentException("The duration must be 0 or greater");
        }
        Mutils.notNull("unit", unit);
        this.pongTimeoutMillis = unit.toMillis(duration);
        return this;
    }

    /**
     * Sets the maximum size in bytes that a frame can be. Defaults to <code>65536</code>.
     *
     * <p>Note that a full message may be multiple frames, and therefore larger than this value.
     * The maximum full message length is configured with {@link #maxMessageLength}.</p>
     *
     * @param maxFramePayloadLength The maximum allowed size in bytes of websocket frames.
     * @return This builder
     */
    public WebSocketHandlerBuilder withMaxFramePayloadLength(int maxFramePayloadLength) {
        if (maxFramePayloadLength < 1024) {
            throw new IllegalArgumentException("The maxFramePayloadLength must be at least 1024 bytes");
        }
        this.maxFramePayloadLength = maxFramePayloadLength;
        return this;
    }

    /**
     * Sets the maximum size in bytes that a full message can be. Defaults to <code>65536</code>.
     *
     * <p>Note that a full message may be multiple frames. The maximum frame size is configured separately
     * with {@link #withMaxFramePayloadLength(int)}.</p>
     *
     * @param maxMessageLength The maximum allowed size in bytes of full websocket messages.
     * @return This builder
     */
    public WebSocketHandlerBuilder withMaxMessageLength(long maxMessageLength) {
        if (maxMessageLength < 1024) {
            throw new IllegalArgumentException("The maxFramePayloadLength must be at least 1024 bytes");
        }
        this.maxMessageLength = maxMessageLength;
        return this;
    }


    /**
     * Creates the websocket handler.
     * @return A websocket handler
     */
    @Override
    public WebSocketHandler build() {
        if (factory == null) {
            throw new IllegalStateException("A web socket factory must be specified");
        }
        var settings = new Settings(pingIntervalMillis, pongTimeoutMillis, maxFramePayloadLength, maxMessageLength);
        return new WebSocketHandler(factory, path, settings);
    }

    /**
     * Creates a new handler builder.
     * @return A new handler builder
     */
    public static WebSocketHandlerBuilder webSocketHandler() {
        return new WebSocketHandlerBuilder();
    }

    /**
     * Creates a new handler builder with the given factory.
     * @param factory The factory to use.
     * @return A new handler builder.
     */
    public static WebSocketHandlerBuilder webSocketHandler(MuWebSocketFactory factory) {
        return webSocketHandler().withWebSocketFactory(factory);
    }

    static class Settings {

        final long pingIntervalMillis;
        final long pongTimeoutMillis;
        final int maxFramePayloadLength;
        final long maxMessageLength;

        public Settings(long pingIntervalMillis, long pongTimeoutMillis, int maxFramePayloadLength, long maxMessageLength) {
            this.pingIntervalMillis = pingIntervalMillis;
            this.pongTimeoutMillis = pongTimeoutMillis;
            this.maxFramePayloadLength = maxFramePayloadLength;
            this.maxMessageLength = maxMessageLength;
        }

        @Override
        public String toString() {
            return "Settings{" +
                "pingIntervalMillis=" + pingIntervalMillis +
                ", pongTimeoutMillis=" + pongTimeoutMillis +
                ", maxFramePayloadLength=" + maxFramePayloadLength +
                ", maxMessageLength=" + maxMessageLength +
                '}';
        }
    }
}

