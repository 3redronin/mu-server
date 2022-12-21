package io.muserver.rest;

import io.muserver.openapi.SchemaObject;

import javax.ws.rs.core.MediaType;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Provides the context that a {@link SchemaObject} is in when customizing the schema in a {@link SchemaObjectCustomizer}
 */
public class SchemaObjectCustomizerContext {

    private final SchemaObjectCustomizerTarget target;
    private final Class<?> type;
    private final Type parameterizedType;
    private final Object resource;
    private final Method method;
    private final String parameter;
    private final MediaType mediaType;

    SchemaObjectCustomizerContext(SchemaObjectCustomizerTarget target, Class<?> type, Type parameterizedType, Object resource, Method method, String parameter, MediaType mediaType) {
        this.target = requireNonNull(target, "target");
        this.type = requireNonNull(type, "type");
        this.parameterizedType = parameterizedType;
        this.resource = resource;
        this.method = method;
        this.parameter = parameter;
        this.mediaType = requireNonNull(mediaType, "mediaType");
    }

    /**
     * @return The type of object being described, e.g. a request body or response body.
     */
    public SchemaObjectCustomizerTarget target() {
        return target;
    }

    /**
     * For normal resources, this is the instance passed to the {@link RestHandlerBuilder}. Note that for sub-resources
     * returned by a sub-resource-locator, this will be null.
     * @return The rest resource that creates or consumes the object being described
     */
    public Object resource() {
        return resource;
    }

    /**
     * @return The java method that builds or consumes the object being described
     */
    public Optional<Method> methodHandle() {
        return Optional.ofNullable(method);
    }

    /**
     * @return Where {@link #target()} is {@link SchemaObjectCustomizerTarget#FORM_PARAM}, this returns the form
     * parameter name.
     */
    public Optional<String> parameterName() {
        return Optional.ofNullable(parameter);
    }

    /**
     * @return The java type of the object being described
     */
    public Class<?> type() {
        return type;
    }

    /**
     * @return For generic types, this is the generic type parameter
     */
    public Optional<Type> parameterizedType() {
        return Optional.ofNullable(parameterizedType);
    }

    /**
     * @return The media type of the schema being described
     */
    public MediaType mediaType() {
        return mediaType;
    }

    @Override
    public String toString() {
        return "SchemaObjectCustomizerContext{" +
            "target=" + target +
            ", type=" + type +
            ", parameterizedType=" + parameterizedType +
            ", resource=" + resource +
            ", method=" + method +
            ", parameter=" + parameter +
            ", mediaType=" + mediaType +
            '}';
    }
}
