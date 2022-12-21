package io.muserver;

/**
 * Thrown when a websocket frame is received that is invalid for any reason.
 */
@Deprecated
public class WebSocketProtocolException extends RuntimeException {

    /**
     * Deprecated
     * @param message The message
     * @param cause The cause
     */
    @Deprecated
    public WebSocketProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
