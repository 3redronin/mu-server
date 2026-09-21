package io.muserver.openapi;

import org.jspecify.annotations.Nullable;

import java.util.Map;

import static io.muserver.openapi.OpenApiUtils.immutable;

/**
 * A map of possible out-of band callbacks related to the parent operation. Each value in the map is a Path Item Object
 * that describes a set of requests that may be initiated by the API provider and the expected responses. The key value
 * used to identify the callback object is an expression, evaluated at runtime, that identifies a URL to use for the callback operation.
 */
public class CallbackObjectBuilder {
    private @Nullable Map<String, Object> extensions;
    private @Nullable Map<String, PathItemObject> callbacks;

    /**
     *
     * @param callbacks A mapping of runtime expressions to path items, or null to clear the callbacks.
     *                  See <a href="https://spec.openapis.org/oas/v3.1.2.html#callback-object">the spec</a>
     *                  for details.
     *
     * @return The current builder
     */
    public CallbackObjectBuilder withCallbacks(@Nullable Map<String, PathItemObject> callbacks) {
        this.callbacks = callbacks;
        return this;
    }

    /**
     * Creates the object
     * @return A new {@link CallbackObject}
     */
    public CallbackObject build() {
        return new CallbackObject(immutable(callbacks), extensions);
    }

    /**
     * Creates a builder for a {@link CallbackObject}
     *
     * @return A new builder
     */
    public static CallbackObjectBuilder callbackObject() {
        return new CallbackObjectBuilder();
    }
    /**
     * @param value the extensions value
     * @return this builder */
    public CallbackObjectBuilder withExtensions(@Nullable Map<String, Object> value) { this.extensions = value; return this; }
    /**
     * @param name an x- extension name
     * @param value its JSON value
     * @return this builder */
    public CallbackObjectBuilder withExtension(String name, @Nullable Object value) {
        Map<String, Object> copy = new java.util.LinkedHashMap<>();
        if (extensions != null) copy.putAll(extensions);
        Extensions.put(copy, name, value);
        extensions = copy;
        return this;
    }
}
