package io.muserver;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;


/**
 * Use this to serve a list of handlers from a base path.
 */
@NullMarked
public class ContextHandlerBuilder implements MuHandlerBuilder<ContextHandler> {
    @Nullable
    private String path;
    private final List<MuHandler> handlers = new ArrayList<>();

    /**
     * Sets the path to serve from.
     * <p>If a null or empty path is given, then it is as if the child handlers are not added to a context.</p>
     *
     * @param path The path, such as <code>api</code> or <code>/api/</code> etc
     * @return Returns the current builder.
     */
    public ContextHandlerBuilder withPath(@Nullable String path) {
        this.path = path;
        return this;
    }

    /**
     * <p>Create a new base path. Any handlers added with {@link #addHandler(MuHandler)}, {@link #addHandler(MuHandlerBuilder)} or
     * {@link #addHandler(Method, String, RouteHandler)} will be served relative to the path given.</p>
     * <p>Request handlers can get the context they are served from by using the {@link MuRequest#contextPath()} and
     * can get the path relative to handler with {@link MuRequest#relativePath()}.</p>
     * <p>If a <code>null</code> or empty path is given, then it is as if the child handlers are not added to a context.</p>
     *
     * @param path The path to serve handlers from, for example <code>api</code> or <code>/api/</code> (which are equivalent).
     * @return Returns a builder with methods to add handlers to this context.
     */
    public static ContextHandlerBuilder context(@Nullable String path) {
        return new ContextHandlerBuilder()
            .withPath(path);
    }

    /**
     * <p>Adds a request handler relative to the context of this builder.</p>
     * <p>Note that handlers are executed in the order added to the builder, but all async
     * handlers are executed before synchronous handlers.</p>
     *
     * @param handler A handler builder. The <code>build()</code> method will be called on this
     *                to create the handler. If this is <code>null</code> then this is a no-op.
     * @return The current Mu-Server Handler.
     * @see #addHandler(Method, String, RouteHandler)
     */
    public ContextHandlerBuilder addHandler(@Nullable MuHandlerBuilder handler) {
        if (handler == null) {
            return this;
        }
        return addHandler(handler.build());
    }

    /**
     * <p>Adds a request handler relative to the context of this builder.</p>
     * <p>Note that handlers are executed in the order added to the builder, but all async
     * handlers are executed before synchronous handlers.</p>
     *
     * @param handler The handler to add. If this is <code>null</code> then this is a no-op.
     * @return The current Mu-Server Handler.
     * @see #addHandler(Method, String, RouteHandler)
     */
    public ContextHandlerBuilder addHandler(@Nullable MuHandler handler) {
        if (handler != null) {
            handlers.add(handler);
        }
        return this;
    }

    /**
     * Registers a new handler that will only be called if it matches the given route info (relative to the current context).
     *
     * @param method      The method to match, or <code>null</code> to accept any method.
     * @param uriTemplate A URL template, relative to the context. Supports plain URLs like <code>/abc</code> or paths
     *                    with named parameters such as <code>/abc/{id}</code> or named parameters
     *                    with regexes such as <code>/abc/{id : [0-9]+}</code> where the named
     *                    parameter values can be accessed with the <code>pathParams</code>
     *                    parameter in the route handler.
     * @param handler     The handler to invoke if the method and URI matches. If this is <code>null</code> then this is a no-op.
     * @return Returns the server builder
     */
    public ContextHandlerBuilder addHandler(@Nullable Method method, String uriTemplate, @Nullable RouteHandler handler) {
        if (handler == null) {
            return this;
        }
        return addHandler(Routes.route(method, uriTemplate, handler));
    }

    @Override
    public ContextHandler build() {
        return new ContextHandler(path, handlers);
    }
}
