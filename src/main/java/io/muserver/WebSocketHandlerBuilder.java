package io.muserver;

public class WebSocketHandlerBuilder implements MuHandlerBuilder<WebSocketHandler> {

    private MuWebSocketFactory factory;

    public WebSocketHandlerBuilder withWebSocketFactory(MuWebSocketFactory factory) {
        this.factory = factory;
        return this;
    }

    @Override
    public WebSocketHandler build() {
        if (factory == null) {
            throw new IllegalStateException("A web socket factory must be specified");
        }
        return new WebSocketHandler(factory);
    }

    public static WebSocketHandlerBuilder webSocketHandler() {
        return new WebSocketHandlerBuilder();
    }
}
