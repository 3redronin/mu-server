package io.muserver;

import io.muserver.rest.PathMatch;
import io.muserver.rest.UriPattern;

public class Routes {
	public static MuHandler route(Method method, String uriTemplate, MuHandler muHandler) {
        UriPattern uriPattern = UriPattern.uriTemplateToRegex(uriTemplate);

        return (request, response) -> {
			boolean methodMatches = method == null || method.equals(request.method());
			if (methodMatches) {
                PathMatch matcher = uriPattern.matcher(request.uri());
                if (matcher.fullyMatches()) {
                    ((NettyRequestAdapter)request).pathParams(matcher.params());
                    return muHandler.handle(request, response);
                }
			}
			return false;
		};
	}

	private Routes() {}
}
