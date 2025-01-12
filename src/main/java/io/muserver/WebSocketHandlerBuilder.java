package io.muserver;

import org.jspecify.annotations.Nullable;

import java.util.concurrent.TimeUnit;

/**
 * <p>Used to create handlers for web sockets.</p>
 */
public class WebSocketHandlerBuilder implements MuHandlerBuilder<WebSocketHandler> {

    private @Nullable MuWebSocketFactory factory;
    private @Nullable String path;
    private int readTimeoutMills = (int)TimeUnit.MINUTES.toMillis(5);
    private long pingIntervalMillis = TimeUnit.SECONDS.toMillis(60);
    private int maxFramePayloadLength = 65536;
    private long maxMessageLength = -1;

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
    public WebSocketHandlerBuilder withPath(@Nullable String path) {
        if (Mutils.nullOrEmpty(path)) {
            this.path = null;
        } else if (path.startsWith("/")) {
            this.path = path;
        } else {
            this.path = "/" + path;
        }
        return this;
    }

    /**
     * Sets the idle read timeout. If no messages are received within this time then the connection is closed.
     * <p>The default is 5 minutes.</p>
     * <p>Note that by default ping messages will be sent (configured with {@link #withPingInterval(int, TimeUnit)})
     * which will cause compliant clients to respond frequently with pong responses. When this idle timeout is
     * longer than the ping interval, it means the timeouts will only detect lost peers (as opposed to a situation
     * where the peer is connected but not sending any messages). Therefore, even if it is normal for a peer to not
     * send any binary or text messages in a long time period, it is still recommended to keep this value relatively
     * small (or at its default) so that non-gracefully shut down clients can be detected.</p>
     * @param duration The allowed timeout duration, or 0 to disable timeouts.
     * @param unit The unit of the duration.
     * @return This builder
     */
    public WebSocketHandlerBuilder withIdleReadTimeout(int duration, TimeUnit unit) {
        if (duration < 0) {
            throw new IllegalArgumentException("The duration must be 0 or greater");
        }
        Mutils.notNull("unit", unit);
        long lv = unit.toMillis(duration);
        if (lv > Integer.MAX_VALUE) {
            lv = Integer.MAX_VALUE; // hmm
        }
        this.readTimeoutMills = (int) lv;
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
     * <p>The default is 60 seconds.</p>
     *
     * <p>Ping messages are used as a keep-alive mechanism and as a way to detect disconnected clients.
     * With the default settings, this ping interval is less than the idle read timeout, meaning that
     * assuming no connectivity issues connected clients will not time out.</p>
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
     * Sets the maximum size in bytes that a full message can be.
     *
     * <p>If not set then the maximum frame size is used.</p>
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
        long mml = maxMessageLength == -1 ? maxFramePayloadLength : maxMessageLength;
        var settings = new Settings(pingIntervalMillis, maxFramePayloadLength, mml, readTimeoutMills);
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
        final int maxFramePayloadLength;
        final long maxMessageLength;
        final int idleReadTimeoutMillis;

        Settings(long pingIntervalMillis, int maxFramePayloadLength, long maxMessageLength, int idleReadTimeoutMillis) {
            this.pingIntervalMillis = pingIntervalMillis;
            this.maxFramePayloadLength = maxFramePayloadLength;
            this.maxMessageLength = maxMessageLength;
            this.idleReadTimeoutMillis = idleReadTimeoutMillis;
        }

        @Override
        public String toString() {
            return "Settings{" +
                "pingIntervalMillis=" + pingIntervalMillis +
                ", maxFramePayloadLength=" + maxFramePayloadLength +
                ", idleReadTimeoutMillis=" + idleReadTimeoutMillis +
                ", maxMessageLength=" + maxMessageLength +
                '}';
        }
    }
}

