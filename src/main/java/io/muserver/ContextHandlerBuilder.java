package io.muserver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ContextHandlerBuilder implements MuHandlerBuilder<ContextHandler> {
    private String path;
    private List<MuHandler> handlers = new ArrayList<>();

    public ContextHandlerBuilder withPath(String path) {
        this.path = path;
        return this;
    }

    public static ContextHandlerBuilder context(String path, MuHandler... handlers) {
        return new ContextHandlerBuilder()
            .withPath(path)
            .withHandlers(handlers);
    }
    public static ContextHandlerBuilder context(String path, MuHandlerBuilder... handlers) {
        return new ContextHandlerBuilder()
            .withPath(path)
            .withHandlers(handlers);
    }

    private ContextHandlerBuilder withHandlers(MuHandler... handlers) {
        this.handlers.addAll(Arrays.asList(handlers));
        return this;
    }
    private ContextHandlerBuilder withHandlers(MuHandlerBuilder... handlers) {
        for (MuHandlerBuilder handler : handlers) {
            this.addHandler(handler);
        }
        return this;
    }


    public ContextHandlerBuilder addHandler(MuHandlerBuilder handler) {
        return addHandler(handler.build());
    }
    public ContextHandlerBuilder addHandler(MuHandler handler) {
        handlers.add(handler);
        return this;
    }

    /**
     * Registers a new handler that will only be called if it matches the given route info
     * @param method The method to match, or <code>null</code> to accept any method.
     * @param uriTemplate A URL template. Supports plain URLs like <code>/abc</code> or paths
     *                   with named parameters such as <code>/abc/{id}</code> or named parameters
     *                    with regexes such as <code>/abc/{id : [0-9]+}</code> where the named
     *                    parameter values can be accessed with the <code>pathParams</code>
     *                    parameter in the route handler.
     * @param handler The handler to invoke if the method and URI matches.
     * @return Returns the server builder
     */
    public ContextHandlerBuilder addHandler(Method method, String uriTemplate, RouteHandler handler) {
        return addHandler(Routes.route(method, uriTemplate, handler));
    }


    @Override
    public ContextHandler build() {
        return new ContextHandler(path, handlers);
    }
}
