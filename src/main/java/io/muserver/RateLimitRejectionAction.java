package io.muserver;

/**
 * Specifies what to do when a rate limit is exceeded
 */
public enum RateLimitRejectionAction {
    /**
     * Nothing happens except the event is logged. Useful to detect when a rate limit would be exceeded for a given system without causing any impact.
     */
    IGNORE,

    /**
     * An HTTP 429 response is sent to the client
     */
    SEND_429,

    /**
     * The client connection is closed immediately
     */
    CLOSE_CONNECTION
}
