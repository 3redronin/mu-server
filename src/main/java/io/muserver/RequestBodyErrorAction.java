package io.muserver;

/**
 * Defines what to do when a client sends an invalid request body.
 *
 * @see MuServerBuilder#withRequestBodyTooLargeAction(RequestBodyErrorAction)
 */
public enum RequestBodyErrorAction {

    /**
     * The client is sent a valid HTTP response, for example a 413 if the request body was too large.
     * <p>Note that the remaining body will be read and discarded so that the response can be successfully sent to the client.</p>
     * <p>This is a good option when you want to communicate back to the client the reason for their failed request, however
     * because the entire request body is still read (and discarded by mu-server) it is less efficient than killing the connection.</p>
     */
    SEND_RESPONSE,

    /**
     * As soon as a request body error is detected, the connection is killed..
     * <p>The client will have some kind of connection reset exception and will not see the reason for the server rejecting
     * the request.</p>
     * <p><strong>Note: </strong> in HTTP/2 this will kill all active requests sharing the same connection.</p>
     */
    KILL_CONNECTION

}
