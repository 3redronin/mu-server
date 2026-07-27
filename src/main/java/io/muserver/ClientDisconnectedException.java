package io.muserver;

/**
 * Thrown when a client non-gracefully disconnects.
 */
public class ClientDisconnectedException extends RuntimeException {
    /**
     * Creates an exception indicating that the client disconnected unexpectedly.
     */
    public ClientDisconnectedException() {
    }
}
