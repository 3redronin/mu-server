package io.muserver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Use this to serve a list of handlers from a base path.
 */
public class ContextHandlerBuilder implements MuHandlerBuilder<ContextHandler> {
    private String path;
    private List<MuHandler> handlers = new ArrayList<>();

    /**
     * Sets the path to serve from.
     * @param path The path, such as <code>api</code> or <code>/api/</code> etc
     * @return Returns the current builder.
     */
    public ContextHandlerBuilder withPath(String path) {
        this.path = path;
        return this;
    }

    /**
     * <p>Create a new base path. Any handlers added with {@link #addHandler(MuHandler)}, {@link #addHandler(MuHandlerBuilder)} or
     * {@link #addHandler(Method, String, RouteHandler)} will be served relative to the path given.</p>
     * <p>Request handlers can get the context they are served from by using the {@link MuRequest#contextPath()} and
     * can get the path relative to handler with {@link MuRequest#relativePath()}.</p>
     * @param path The path to serve handlers from, for example <code>api</code> or <code>/api/</code> (which are equivalent).
     * @return Returns a builder with methods to add handlers to this context.
     */
    public static ContextHandlerBuilder context(String path) {
        return new ContextHandlerBuilder()
            .withPath(path);
    }

    /**
     * @param path The path
     * @param handlers The handler
     * @return A context handler that you can add handlers to
     * @deprecated Use {@link #context(String)} and then add handlers on to that.
     */
    @Deprecated
    public static ContextHandlerBuilder context(String path, MuHandler... handlers) {
        return new ContextHandlerBuilder()
            .withPath(path)
            .withHandlers(handlers);
    }
    /**
     * @param path The path
     * @param handlers The handler
     * @return A context handler that you can add handlers to
     * @deprecated Use {@link #context(String)} and then add handlers on to that.
     */
    @Deprecated
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

    /**
     * <p>Adds a request handler relative to the context of this builder.</p>
     * <p>Note that handlers are executed in the order added to the builder, but all async
     * handlers are executed before synchronous handlers.</p>
     * @see #addHandler(Method, String, RouteHandler)
     * @param handler A handler builder. The <code>build()</code> method will be called on this
     *                to create the handler.
     * @return The current Mu-Server Handler.
     */
    public ContextHandlerBuilder addHandler(MuHandlerBuilder handler) {
        return addHandler(handler.build());
    }

    /**
     * <p>Adds a request handler relative to the context of this builder.</p>
     * <p>Note that handlers are executed in the order added to the builder, but all async
     * handlers are executed before synchronous handlers.</p>
     * @see #addHandler(Method, String, RouteHandler)
     * @param handler The handler to add.
     * @return The current Mu-Server Handler.
     */
    public ContextHandlerBuilder addHandler(MuHandler handler) {
        handlers.add(handler);
        return this;
    }

    /**
     * Registers a new handler that will only be called if it matches the given route info (relative to the current context).
     * @param method The method to match, or <code>null</code> to accept any method.
     * @param uriTemplate A URL template, relative to the context. Supports plain URLs like <code>/abc</code> or paths
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
