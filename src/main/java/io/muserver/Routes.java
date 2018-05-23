package io.muserver;

import io.muserver.rest.PathMatch;
import io.muserver.rest.UriPattern;

/**
 * A helper class to create a handler for a specific URL. See{@link MuServerBuilder#addHandler(Method, String, RouteHandler)}
 * for a simple way to add a routed handler to a server.
 */
public class Routes {

    /**
     * Creates a new handler that will only be called if it matches the given route info.
     * @param method The method to match, or <code>null</code> to accept any method.
     * @param uriTemplate A URL template. Supports plain URLs like <code>/abc</code> or paths
     *                   with named parameters such as <code>/abc/{id}</code> or named parameters
     *                    with regexes such as <code>/abc/{id : [0-9]+}</code> where the named
     *                    parameter values can be accessed with the <code>pathParams</code>
     *                    parameter in the route handler.
     * @param muHandler The handler to invoke if the method and URI matches.
     * @return Returns a {@link MuHandler} that is only called if the request URI and method matches.
     * @see MuServerBuilder#addHandler(Method, String, RouteHandler)
     */
	public static MuHandler route(Method method, String uriTemplate, RouteHandler muHandler) {
        UriPattern uriPattern = UriPattern.uriTemplateToRegex(uriTemplate);

        return (request, response) -> {
			boolean methodMatches = method == null || method.equals(request.method());
			if (methodMatches) {
                PathMatch matcher = uriPattern.matcher(request.relativePath());
                if (matcher.fullyMatches()) {
                    muHandler.handle(request, response, matcher.params());
                    return true;
                }
			}
			return false;
		};
	}

	private Routes() {}
}
