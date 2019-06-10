package io.muserver;

/**
 * Thrown when a websocket frame is received that is invalid for any reason.
 */
public class WebSocketProtocolException extends RuntimeException {

    public WebSocketProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
