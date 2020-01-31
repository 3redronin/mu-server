package io.muserver.handlers;

import io.muserver.Headers;
import io.muserver.MuRequest;

/**
 * A hook to change responses for static resources.
 * <p>This is primarily used to customize the headers of static files. Register this hook with {@link ResourceHandlerBuilder#withResourceCustomizer(ResourceCustomizer)}</p>
 */
public interface ResourceCustomizer {

    /**
     * Called after the default headers have been set, just before they are sent to the client. Changing the passed in
     * headers map will change the response headers sent to the client.
     * @param request The client request
     * @param responseHeaders The headers that will be sent to the client, which can be modified in this method
     */
    default void beforeHeadersSent(MuRequest request, Headers responseHeaders) {};
}
