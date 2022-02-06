package io.muserver.handlers;

import io.muserver.Method;
import io.muserver.MuHandler;
import io.muserver.MuRequest;
import io.muserver.MuResponse;
import io.muserver.rest.CORSConfig;

import java.util.Set;

/**
 * A handler that adds CORS headers to responses. Create a builder with {@link CORSHandlerBuilder#corsHandler()}
 */
public class CORSHandler implements MuHandler {
    private final CORSConfig corsConfig;
    private final Set<Method> allowedMethods;

    CORSHandler(CORSConfig corsConfig, Set<Method> allowedMethods) {
        this.corsConfig = corsConfig;
        this.allowedMethods = allowedMethods;
    }

    @Override
    public boolean handle(MuRequest request, MuResponse response) {
        corsConfig.writeHeaders(request, response, allowedMethods);
        return false;
    }

    @Override
    public String toString() {
        return "CORSHandler{" +
            "corsConfig=" + corsConfig +
            ", allowedMethods=" + allowedMethods +
            '}';
    }
}
