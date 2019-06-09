package io.muserver;

/**
 * <p>Used to create handlers for web sockets.</p>
 */
public class WebSocketHandlerBuilder implements MuHandlerBuilder<WebSocketHandler> {

    private MuWebSocketFactory factory;
    private String path;

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
     * Creates the websocket handler.
     * @return A websocket handler
     */
    @Override
    public WebSocketHandler build() {
        if (factory == null) {
            throw new IllegalStateException("A web socket factory must be specified");
        }
        return new WebSocketHandler(factory, path);
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
}
