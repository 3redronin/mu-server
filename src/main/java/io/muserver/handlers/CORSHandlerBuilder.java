package io.muserver.handlers;

import io.muserver.Method;
import io.muserver.MuHandlerBuilder;
import io.muserver.Mutils;
import io.muserver.rest.CORSConfig;
import io.muserver.rest.CORSConfigBuilder;

import java.util.HashSet;
import java.util.Set;

import static java.util.Arrays.asList;

/**
 * <p>Used to create a handler that puts appropriate CORS headers on requests.</p>
 * <p>Simply add this handler before any other handlers that you want CORS headers added to.</p>
 */
public class CORSHandlerBuilder implements MuHandlerBuilder<CORSHandler> {

    private CORSConfig corsConfig;
    private Set<Method> allowedMethods;

    /**
     * Sets the CORS configuration for the handler
     * @param corsConfig The config to use
     * @return This builder
     */
    public CORSHandlerBuilder withCORSConfig(CORSConfig corsConfig) {
        Mutils.notNull("corsConfig", corsConfig);
        this.corsConfig = corsConfig;
        return this;
    }

    /**
     * Sets the CORS configuration for the handler
     * @param corsConfig The config to use
     * @return This builder
     */
    public CORSHandlerBuilder withCORSConfig(CORSConfigBuilder corsConfig) {
        return withCORSConfig(corsConfig.build());
    }

    /**
     * Specifies the headers allowed for CORS requests. Defaults to all methods except Trace and Connect.
     * @param methods The methods to allow, or null to allow all except Trace and Connect.
     * @return This builder.
     */
    public CORSHandlerBuilder withAllowedMethods(Method... methods) {
        if (methods == null) {
            allowedMethods = null;
        } else {
            allowedMethods = new HashSet<>(asList(methods));
        }
        return this;
    }

    /**
     * A helper for creating a config object to pass to {@link #withCORSConfig(CORSConfigBuilder)}
     * @return a CORS configuration object
     */
    public static CORSConfigBuilder config() {
        return CORSConfigBuilder.corsConfig();
    }

    /**
     * A helper for creating a CORS handler
     * @return A new builder.
     */
    public static CORSHandlerBuilder corsHandler() {
        return new CORSHandlerBuilder();
    }

    @Override
    public CORSHandler build() {
        Set<Method> methods = this.allowedMethods;
        if (methods == null || methods.isEmpty()) {
            methods = new HashSet<>(asList(Method.values()));
            methods.remove(Method.TRACE);
            methods.remove(Method.CONNECT);
        }
        if (corsConfig == null) {
            throw new IllegalStateException("You must specify the CORS config");
        }
        return new CORSHandler(corsConfig, methods);
    }
}
