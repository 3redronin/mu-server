package io.muserver.openapi;

import java.util.Map;

/**
 * A map of possible out-of band callbacks related to the parent operation. Each value in the map is a Path Item Object
 * that describes a set of requests that may be initiated by the API provider and the expected responses. The key value
 * used to identify the callback object is an expression, evaluated at runtime, that identifies a URL to use for the callback operation.
 */
public class CallbackObjectBuilder {
    private Map<String, PathItemObject> callbacks;

    /**
     * @param callbacks A mapping of runtime expressions to path items.
     *                  See <a href="https://github.com/OAI/OpenAPI-Specification/blob/master/versions/3.0.1.md#callback-object">the spec</a>
     *                  for details.
     * @return The current builder
     */
    public CallbackObjectBuilder withCallbacks(Map<String, PathItemObject> callbacks) {
        this.callbacks = callbacks;
        return this;
    }

    public CallbackObject build() {
        return new CallbackObject(callbacks);
    }

    /**
     * Creates a builder for a {@link CallbackObject}
     *
     * @return A new builder
     */
    public static CallbackObjectBuilder callbackObject() {
        return new CallbackObjectBuilder();
    }
}