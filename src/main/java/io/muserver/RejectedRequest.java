package io.muserver;

/**
 * Information about a request that was rejected before it became a normal request/response
 * exchange (for example a <code>431</code> when the request headers are too large).
 * @see RequestRejectListener
 */
public interface RejectedRequest {

    /**
     * @return The HTTP status code sent to the client, for example <code>431</code> or <code>503</code>.
     */
    int status();

    /**
     * @return The plain-text message sent to the client, for example
     * <code>431 Request Header Fields Too Large</code>.
     */
    String reason();

    /**
     * @return The connection the rejected request arrived on, which can be used to find details such as
     * the {@link HttpConnection#remoteAddress()} and {@link HttpConnection#protocol()}.
     */
    HttpConnection connection();

}
