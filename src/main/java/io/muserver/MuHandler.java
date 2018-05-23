package io.muserver;

/**
 * <p>An HTTP request handler. This can be used to inspect HTTP requests and return responses, or act as a filter
 * that intercepts requests before going to subsequent handlers (for logging, adding common response headers, or
 * security filtering etc).</p>
 * <p>Handlers are executed in the order they are registered with Mu-Server. By returning <code>true</code> from a
 * handler you are indicating that the handler processing should stop; <code>false</code> means to go to the next
 * handler.</p>
 * <p>This type of handler allows you to look at the request path and decide whether to take action or not. Note
 * that if you want a handler for a specific URL you may consider using {@link MuServerBuilder#addHandler(io.muserver.Method, java.lang.String, io.muserver.RouteHandler)}
 * instead.</p>
 */
public interface MuHandler {

    /**
     * Called when an HTTP request is made (unless a previous handler stopped handler processing)
     *
     * @param request  The HTTP request.
     * @param response The HTTP response.
     * @return Return <code>false</code> to continue processing the next handler (for example if writing a filter or inspector); or <code>true</code> to stop processing (normally done if this handler sent a response).
     * @throws Exception Any uncaught exceptions will result in a 500 error code being returned to the client with a simple message.
     */
    boolean handle(MuRequest request, MuResponse response) throws Exception;

}
