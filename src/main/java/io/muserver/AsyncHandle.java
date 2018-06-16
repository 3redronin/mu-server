package io.muserver;

/**
 * A class to handle the request and response handling when using asynchronous request handling.
 */
public interface AsyncHandle {

    /**
     * <p>Sets a listener that will be notified when chunks of request data become available.</p>
     * <p>If this is not set, then the usual (blocking) request reading methods on the request object can be used.</p>
     * @param readListener The listener.
     */
    void setReadListener(RequestBodyListener readListener);

    /**
     * Call this to indicate that the response is complete.
     */
    void complete();

}
