package io.muserver;

/**
 * A handler for exceptions that have been thrown by other handlers which allows for custom error pages.
 * <p>This is registered with {@link MuServerBuilder#withExceptionHandler(UnhandledExceptionHandler)}.</p>
 * <p>Note: {@link javax.ws.rs.RedirectionException}s will not get routed to this handler.</p>
 */
public interface UnhandledExceptionHandler {

    /**
     * Called when an exception is thrown by another handler.
     * <p>Note that if the response has already started sending data, you will not be able to add a custom error
     * message. In this case, you may want to allow for the default error handling by returning <code>false</code>.</p>
     * <p>The following shows a pattern to filter out certain errors:</p>
     * <pre><code>
     * muServerBuilder.withExceptionHandler((request, response, exception) -> {
     *                     if (response.hasStartedSendingData()) return false; // cannot customise the response
     *                     if (exception instanceof NotAuthorizedException) return false;
     *                     response.contentType(ContentTypes.TEXT_PLAIN_UTF8);
     *                     response.write("Oh I'm worry, there was a problem");
     *                     return true;
     *                 })
     * </code></pre>
     * @param request The request
     * @param response The response
     * @param cause The exception thrown by an earlier handler
     * @return <code>true</code> if this handler has written a response; otherwise <code>false</code> in which case
     * the default error handler will be invoked.
     * @throws Exception Throwing an exception will result in a <code>500</code> error code being returned with a basic error message.
     */
    boolean handle(MuRequest request, MuResponse response, Throwable cause) throws Exception;

}
